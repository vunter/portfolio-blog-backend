package dev.catananti.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * BUG-02 FIX: Service to blacklist JWT access tokens on logout.
 * Uses Redis with TTL matching the token's remaining lifetime so entries
 * auto-expire when the token would have expired anyway.
 * TODO F-242: Add fallback for Redis downtime — consider local in-memory blacklist with sync
 */
@Service
@Slf4j
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public TokenBlacklistService(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Add a JWT token ID to the blacklist with a TTL matching the token's remaining lifetime.
     *
     * @param jti            the JWT ID (jti claim)
     * @param remainingMs    milliseconds until the token expires naturally
     */
    public Mono<Boolean> blacklist(String jti, long remainingMs) {
        if (jti == null || jti.isBlank() || remainingMs <= 0) {
            return Mono.just(false);
        }
        String key = BLACKLIST_PREFIX + jti;
        Duration ttl = Duration.ofMillis(remainingMs);
        return redisTemplate.opsForValue()
                .set(key, "1", ttl)
                .doOnSuccess(ok -> log.debug("Blacklisted JWT jti={} ttl={}ms", jti, remainingMs))
                .onErrorResume(e -> {
                    log.error("Failed to blacklist JWT jti={}: {}", jti, e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * Check if a JWT token ID is blacklisted.
     *
     * @param jti the JWT ID (jti claim)
     * @return true if blacklisted (token should be rejected)
     */
    public Mono<Boolean> isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return Mono.just(false);
        }
        String key = BLACKLIST_PREFIX + jti;
        return redisTemplate.hasKey(key)
                .onErrorResume(e -> {
                    log.error("Failed to check blacklist for jti={}: {}", jti, e.getMessage());
                    // C3 FIX: Fail-closed — if Redis is down, assume token IS blacklisted (reject it).
                    // This prevents revoked tokens from being accepted when Redis is unavailable.
                    return Mono.just(true);
                });
    }
}
