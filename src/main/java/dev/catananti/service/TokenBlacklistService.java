package dev.catananti.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BUG-02 FIX: Service to blacklist JWT access tokens on logout.
 * Uses Redis with TTL matching the token's remaining lifetime so entries
 * auto-expire when the token would have expired anyway.
 * Falls back to local in-memory blacklist when Redis is unavailable.
 */
@Service
@Slf4j
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    // F-242: In-memory fallback when Redis is down (key=jti, value=expiry timestamp in millis)
    private final ConcurrentHashMap<String, Long> localBlacklist = new ConcurrentHashMap<>();

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
        // Always add to local fallback
        localBlacklist.put(jti, System.currentTimeMillis() + remainingMs);
        cleanupExpiredEntries();

        String key = BLACKLIST_PREFIX + jti;
        Duration ttl = Duration.ofMillis(remainingMs);
        return redisTemplate.opsForValue()
                .set(key, "1", ttl)
                .doOnSuccess(ok -> log.debug("Blacklisted JWT jti={} ttl={}ms", jti, remainingMs))
                .onErrorResume(e -> {
                    log.debug("Redis unavailable for blacklist, using local fallback for jti={}: {}", jti, e.getMessage());
                    return Mono.just(true);
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
                    log.debug("Redis unavailable for blacklist check, using local fallback for jti={}: {}", jti, e.getMessage());
                    // Check local blacklist as fallback
                    Long expiry = localBlacklist.get(jti);
                    if (expiry != null && expiry > System.currentTimeMillis()) {
                        return Mono.just(true);
                    }
                    // Fail-open — token was never explicitly blacklisted, allow it
                    return Mono.just(false);
                });
    }

    /**
     * Remove expired entries from the local blacklist to prevent memory leaks.
     */
    private void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        localBlacklist.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
}
