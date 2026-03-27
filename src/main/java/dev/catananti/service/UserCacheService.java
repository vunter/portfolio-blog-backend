package dev.catananti.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.catananti.entity.User;
import dev.catananti.util.PiiMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Centralized user cache extracted from JwtAuthenticationFilter.
 * Allows services (e.g. UserService) to evict users without depending on the filter.
 * F-046: 60s TTL is a security/performance tradeoff -- deactivated users locked out within 1 minute.
 */
@Service
@Slf4j
public class UserCacheService {

    private final Cache<String, User> userCache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    /**
     * Get a cached user by email, or null if not present.
     */
    public User getIfPresent(String email) {
        return userCache.getIfPresent(email);
    }

    /**
     * Put a user into the cache.
     */
    public void put(String email, User user) {
        userCache.put(email, user);
    }

    /**
     * Evict a user from the cache (e.g. on deactivation or role change).
     */
    public void evict(String email) {
        userCache.invalidate(email);
        log.debug("Evicted user from auth cache: {}", PiiMasker.maskEmail(email));
    }
}
