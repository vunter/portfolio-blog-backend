package dev.catananti.config;

import dev.catananti.util.IpAddressExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Q7.3: Idempotency key filter for state-changing endpoints.
 *
 * <p>When a client sends an {@code X-Idempotency-Key} header (UUID format) with a
 * POST/PUT/PATCH request, this filter ensures the request is processed at most once.
 * Duplicate requests within the TTL window receive a 409 Conflict response.</p>
 *
 * <p>Keys are stored in Redis namespaced by principal: authenticated calls live under
 * {@code idem:u:&lt;userId&gt;:&lt;uuid&gt;}, anonymous calls under {@code idem:a:&lt;ip&gt;:&lt;uuid&gt;}.
 * This prevents one tenant from preempting another's idempotency key as a targeted DoS.</p>
 *
 * <p>If the original request fails, the key is removed to allow retries.</p>
 */
@Component
@Order(5) // After RequestIdFilter, before RateLimitingFilter
@Slf4j
public class IdempotencyKeyFilter implements WebFilter {

    private static final String HEADER = "X-Idempotency-Key";
    private static final String USER_PREFIX = "idem:u:";
    private static final String ANON_PREFIX = "idem:a:";
    private static final Set<HttpMethod> IDEMPOTENT_METHODS = Set.of(
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH
    );

    private final ReactiveStringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final Duration processingTtl;

    public IdempotencyKeyFilter(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${app.idempotency.ttl-hours:24}") int ttlHours,
            @Value("${app.idempotency.processing-ttl-seconds:60}") int processingTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofHours(ttlHours);
        // BUG-5: short-lived marker so a crash mid-processing does not orphan the key for the
        // full TTL (which would 409 every retry forever). The marker is extended to the full
        // TTL only once processing completes successfully.
        this.processingTtl = Duration.ofSeconds(processingTtlSeconds);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (!IDEMPOTENT_METHODS.contains(method)) {
            return chain.filter(exchange);
        }

        String idempotencyKey = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return chain.filter(exchange);
        }

        if (!isValidUuid(idempotencyKey)) {
            return writeJsonError(exchange, HttpStatus.BAD_REQUEST,
                    "Invalid idempotency key format — must be a UUID");
        }

        return resolveRedisKey(exchange, idempotencyKey)
                .flatMap(redisKey -> processWithKey(exchange, chain, redisKey, idempotencyKey));
    }

    private Mono<String> resolveRedisKey(ServerWebExchange exchange, String idempotencyKey) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated()
                        && !"anonymousUser".equals(auth.getName()))
                .map(auth -> USER_PREFIX + auth.getName() + ":" + idempotencyKey)
                .defaultIfEmpty(ANON_PREFIX + IpAddressExtractor.extractClientIp(exchange) + ":" + idempotencyKey);
    }

    private Mono<Void> processWithKey(ServerWebExchange exchange, WebFilterChain chain,
                                      String redisKey, String idempotencyKey) {
        // BUG-5: write the 'processing' marker with a SHORT TTL. If the chain crashes before
        // the success handler runs (orphaning the key), the marker self-expires within the
        // short window instead of blocking retries for the full TTL.
        return redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "processing", processingTtl)
                .flatMap(wasSet -> {
                    if (Boolean.TRUE.equals(wasSet)) {
                        return chain.filter(exchange)
                                .then(Mono.defer(() -> {
                                    HttpStatus status = (HttpStatus) exchange.getResponse().getStatusCode();
                                    String value = "done:" + (status != null ? status.value() : 200);
                                    return redisTemplate.opsForValue()
                                            .set(redisKey, value, ttl)
                                            .onErrorResume(e -> {
                                                log.warn("Failed to update idempotency key {}: {}",
                                                        idempotencyKey, e.getMessage());
                                                return Mono.empty();
                                            })
                                            .then();
                                }))
                                .onErrorResume(e -> {
                                    log.debug("Idempotency key {} removed after error: {}",
                                            idempotencyKey, e.getMessage());
                                    return redisTemplate.delete(redisKey)
                                            .onErrorResume(ex -> Mono.empty())
                                            .then(Mono.error(e));
                                });
                    }

                    log.info("Duplicate request blocked by idempotency key: {}", idempotencyKey);
                    return writeJsonError(exchange, HttpStatus.CONFLICT,
                            "Request with this idempotency key has already been processed");
                });
    }

    private boolean isValidUuid(String key) {
        try {
            UUID.fromString(key);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Mono<Void> writeJsonError(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":\"" + message + "\"}";
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory()
                        .wrap(body.getBytes(StandardCharsets.UTF_8))
        ));
    }
}
