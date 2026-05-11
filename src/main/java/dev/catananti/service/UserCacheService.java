package dev.catananti.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.catananti.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Centralized user cache extracted from JwtAuthenticationFilter.
 * Allows services (e.g. UserService) to evict users without depending on the filter.
 *
 * <p>Keyed by user ID (the {@code sub} claim of the JWT). 60s TTL is a security/performance
 * tradeoff — deactivated users are locked out within 1 minute even without explicit eviction.
 */
@Service
@Slf4j
public class UserCacheService {

    private final Cache<Long, User> userCache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public User getIfPresent(Long userId) {
        return userId != null ? userCache.getIfPresent(userId) : null;
    }

    public void put(Long userId, User user) {
        if (userId != null && user != null) {
            userCache.put(userId, user);
        }
    }

    /**
     * Evict a user from the cache (e.g. on deactivation, role change, or password reset).
     */
    public void evict(Long userId) {
        if (userId == null) return;
        userCache.invalidate(userId);
        log.debug("Evicted userId={} from auth cache", userId);
    }
}
