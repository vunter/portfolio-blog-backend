package dev.catananti.config;

import dev.catananti.security.JwtTokenProvider;
import dev.catananti.util.IpAddressExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
// TODO F-025: Consider sliding window algorithm instead of fixed window to avoid burst-at-boundary issues
public class RateLimitingFilter implements WebFilter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final JwtTokenProvider tokenProvider;
    
    // Configurable rate limits
    private final int maxRequestsAuthenticated;
    private final int maxRequestsAnonymous;
    private final int maxRequestsLogin;
    private final Duration windowDuration;

    // In-memory fallback rate limiter when Redis is unavailable
    private record RateLimitEntry(AtomicLong count, Instant expiresAt) {}
    private final ConcurrentHashMap<String, RateLimitEntry> inMemoryRateLimits = new ConcurrentHashMap<>();

    public RateLimitingFilter(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate, 
            JwtTokenProvider tokenProvider,
            @Value("${rate-limit.authenticated:100}") int maxRequestsAuthenticated,
            @Value("${rate-limit.anonymous:30}") int maxRequestsAnonymous,
            @Value("${rate-limit.login:10}") int maxRequestsLogin,
            @Value("${rate-limit.window-seconds:60}") int windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.tokenProvider = tokenProvider;
        this.maxRequestsAuthenticated = maxRequestsAuthenticated;
        this.maxRequestsAnonymous = maxRequestsAnonymous;
        this.maxRequestsLogin = maxRequestsLogin;
        this.windowDuration = Duration.ofSeconds(windowSeconds);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        
        // Skip rate limiting for actuator health endpoints
        if (path.startsWith("/actuator/health") || path.equals("/livez") || path.equals("/readyz")) {
            return chain.filter(exchange);
        }

        String clientIp = IpAddressExtractor.extractClientIp(exchange);
        String rateLimitKey = buildRateLimitKey(clientIp, path);

        // BUG-CRÍTICO-01: Determine rate limit reactively (removed .block() call)
        return determineRateLimit(exchange, path)
                .flatMap(maxRequests -> redisTemplate.opsForValue()
                        .increment(rateLimitKey)
                        .flatMap(count -> {
                            if (count == 1) {
                                // First request, set expiry
                                return redisTemplate.expire(rateLimitKey, windowDuration)
                                        .thenReturn(count);
                            }
                            return Mono.just(count);
                        })
                        .flatMap(count -> {
                            // BUG-RT7 FIX: Use set() instead of add() to avoid duplicate headers,
                            // and add headers via beforeCommit to ensure they are set before
                            // the response body is written by downstream filters.
                            HttpHeaders headers = exchange.getResponse().getHeaders();
                            
                            if (count > maxRequests) {
                                log.warn("Rate limit exceeded for IP: {}, path: {}, count: {}", clientIp, path, count);
                                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                                headers.set("X-RateLimit-Limit", String.valueOf(maxRequests));
                                headers.set("X-RateLimit-Remaining", "0");
                                headers.set("X-RateLimit-Reset", 
                                        String.valueOf(Instant.now().plus(windowDuration).toEpochMilli()));
                                headers.set("Retry-After", String.valueOf(windowDuration.toSeconds()));
                                return exchange.getResponse().setComplete();
                            }
                            
                            headers.set("X-RateLimit-Limit", String.valueOf(maxRequests));
                            headers.set("X-RateLimit-Remaining", 
                                    String.valueOf(Math.max(0, maxRequests - count)));
                            
                            return chain.filter(exchange);
                        })
                        .onErrorResume(e -> {
                            // Redis unavailable — use in-memory fallback
                            log.warn("Rate limiting Redis unavailable ({}), using in-memory fallback: {}",
                                    e.getClass().getSimpleName(), e.getMessage());
                            return handleInMemoryRateLimit(exchange, chain, rateLimitKey, maxRequests, clientIp, path);
                        })
                );
    }

    /**
     * In-memory fallback rate limiting when Redis is unavailable.
     */
    private Mono<Void> handleInMemoryRateLimit(ServerWebExchange exchange, WebFilterChain chain,
                                                String key, int maxRequests, String clientIp, String path) {
        Instant now = Instant.now();
        RateLimitEntry entry = inMemoryRateLimits.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAt().isBefore(now)) {
                return new RateLimitEntry(new AtomicLong(1), now.plus(windowDuration));
            }
            existing.count().incrementAndGet();
            return existing;
        });

        long count = entry.count().get();
        HttpHeaders headers = exchange.getResponse().getHeaders();

        if (count > maxRequests) {
            log.warn("Rate limit exceeded (in-memory) for IP: {}, path: {}, count: {}", clientIp, path, count);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            headers.set("X-RateLimit-Limit", String.valueOf(maxRequests));
            headers.set("X-RateLimit-Remaining", "0");
            headers.set("Retry-After", String.valueOf(windowDuration.toSeconds()));
            return exchange.getResponse().setComplete();
        }

        headers.set("X-RateLimit-Limit", String.valueOf(maxRequests));
        headers.set("X-RateLimit-Remaining",
                String.valueOf(Math.max(0, maxRequests - count)));
        return chain.filter(exchange);
    }

    /**
     * Periodically clean up expired in-memory rate limit entries (every 5 minutes).
     */
    @Scheduled(fixedRate = 300000)
    public void cleanupExpiredInMemoryEntries() {
        Instant now = Instant.now();
        inMemoryRateLimits.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    /**
     * BUG-CRÍTICO-01: Refactored to return Mono<Integer> instead of blocking.
     * Determines rate limit reactively based on endpoint and authentication status.
     */
    private Mono<Integer> determineRateLimit(ServerWebExchange exchange, String path) {
        // Login and password reset endpoints have stricter limits
        if (path.contains("/auth/login") || path.contains("/forgot-password") || path.contains("/reset-password")) {
            return Mono.just(maxRequestsLogin);
        }

        // Check SecurityContext reactively to avoid blocking on Netty event loop
        return exchange.getPrincipal()
                .map(principal -> maxRequestsAuthenticated)
                .defaultIfEmpty(maxRequestsAnonymous);
    }

    private String buildRateLimitKey(String clientIp, String path) {
        // Use different buckets for login to prevent circumvention
        if (path.contains("/auth/login")) {
            return "rate_limit:login:" + clientIp;
        }
        return "rate_limit:" + clientIp;
    }
}
