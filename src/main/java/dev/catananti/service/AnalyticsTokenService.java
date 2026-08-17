package dev.catananti.service;

import dev.catananti.dto.AnalyticsTokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * SEC-AH-02: Session token service for analytics proof-of-visit.
 * Issues short-lived SESSION tokens that prove a client loaded the site before
 * submitting analytics events. The token is reusable until its TTL — the client
 * caches it against the returned expiresAt and attaches it to every event.
 * Per-event uniqueness/abuse control is the PoW challenge's job (single-use,
 * consumed on verification), not the token's.
 */
@Service
@Slf4j
public class AnalyticsTokenService {

    private static final String TOKEN_KEY_PREFIX = "analytics:token:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final Duration tokenTtl;

    public AnalyticsTokenService(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
            @Value("${analytics.token.ttl-seconds:1800}") int ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.tokenTtl = Duration.ofSeconds(ttlSeconds);
        log.info("Analytics token service initialised (ttl={}s)", ttlSeconds);
    }

    /**
     * Issue a new analytics session token stored in Redis with TTL.
     */
    public Mono<AnalyticsTokenResponse> issueToken() {
        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(tokenTtl);
        String redisKey = TOKEN_KEY_PREFIX + token;

        return redisTemplate.opsForValue()
                .set(redisKey, "1", tokenTtl)
                .thenReturn(AnalyticsTokenResponse.builder()
                        .token(token)
                        .expiresAt(expiresAt)
                        .build())
                .doOnSuccess(t -> log.debug("Analytics token issued"));
    }

    /**
     * Validate a session token. NON-consuming: the token stays valid until its
     * Redis TTL expires, matching the client contract (issueToken returns
     * expiresAt and the frontend reuses the cached token for every event).
     * The old getAndDelete here made every second analytics event 403.
     *
     * @return Mono.empty() if valid, Mono.error() if invalid/expired
     */
    public Mono<Void> validate(String token) {
        if (token == null || token.isBlank()) {
            return Mono.error(new IllegalArgumentException("error.analytics_token_required"));
        }

        String redisKey = TOKEN_KEY_PREFIX + token;

        return redisTemplate.opsForValue().get(redisKey)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("error.analytics_token_invalid")))
                .then()
                .doOnSuccess(v -> log.debug("Analytics token validated"));
    }
}
