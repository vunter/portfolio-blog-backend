package dev.catananti.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.catananti.config.ResilienceConfig;
import dev.catananti.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Service to track and limit login attempts to prevent brute force attacks.
 * Only active when Redis is available (production).
 * <p>
 * F-185: Uses a local Caffeine cache as fallback when Redis is unavailable,
 * preventing fail-open brute-force attacks during Redis outages.
 * The Caffeine cache is per-instance, so in multi-instance deployments
 * an attacker would need to exceed the threshold on each instance separately.
 */
@Service
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
@Slf4j
public class LoginAttemptService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final ResilienceConfig resilience;

    private static final String LOGIN_ATTEMPT_PREFIX = "login_attempt:";
    private static final String LOCKOUT_PREFIX = "lockout:";
    private static final String LOCKOUT_NOTIFIED_PREFIX = "lockout_notified:";
    
    private final int maxAttempts;
    private final Duration attemptWindow;
    private final Duration progressiveLockoutBase;

    // F-185: Caffeine fallback caches (per-JVM, evict after max lockout window)
    private final Cache<String, Integer> localAttempts;
    private final Cache<String, Instant> localLockouts;

    public LoginAttemptService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            UserRepository userRepository,
            EmailService emailService,
            ResilienceConfig resilience,
            @Value("${security.login.max-attempts:5}") int maxAttempts,
            @Value("${security.login.attempt-window-minutes:15}") int attemptWindowMinutes,
            @Value("${security.login.progressive-lockout-base-minutes:5}") int progressiveLockoutBaseMinutes) {
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.resilience = resilience;
        this.maxAttempts = maxAttempts;
        this.attemptWindow = Duration.ofMinutes(attemptWindowMinutes);
        this.progressiveLockoutBase = Duration.ofMinutes(progressiveLockoutBaseMinutes);

        // Max lockout = base * 6 multiplier; attempts cache expires after the attempt window
        long maxLockoutMinutes = progressiveLockoutBaseMinutes * 6L;
        this.localAttempts = Caffeine.newBuilder()
                .expireAfterWrite(attemptWindowMinutes, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();
        this.localLockouts = Caffeine.newBuilder()
                .expireAfterWrite(maxLockoutMinutes, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();
    }

    /**
     * Check if the user/IP is currently locked out.
     * Falls back to local Caffeine cache when Redis is unavailable.
     */
    public Mono<Boolean> isBlocked(String key) {
        return redisTemplate.hasKey(LOCKOUT_PREFIX + key)
                .timeout(resilience.getRedisTimeout())
                .onErrorResume(e -> {
                    log.warn("Redis unavailable for isBlocked check, falling back to Caffeine: {}", e.getMessage());
                    return Mono.fromCallable(() -> isBlockedLocally(key));
                });
    }

    /**
     * Get remaining lockout time in seconds.
     */
    public Mono<Long> getRemainingLockoutTime(String key) {
        return redisTemplate.getExpire(LOCKOUT_PREFIX + key)
                .map(Duration::getSeconds)
                .defaultIfEmpty(0L)
                .timeout(resilience.getRedisTimeout())
                .onErrorResume(e -> {
                    log.warn("Redis unavailable for lockout time, falling back to Caffeine: {}", e.getMessage());
                    return Mono.fromCallable(() -> getRemainingLockoutTimeLocally(key));
                });
    }

    /**
     * Record a failed login attempt (without IP tracking).
     */
    public Mono<Integer> recordFailedAttempt(String key) {
        return recordFailedAttempt(key, null);
    }

    /**
     * Record a failed login attempt with optional IP address for notifications.
     * Falls back to local Caffeine cache when Redis is unavailable.
     */
    public Mono<Integer> recordFailedAttempt(String key, String clientIp) {
        String attemptKey = LOGIN_ATTEMPT_PREFIX + key;
        
        return redisTemplate.opsForValue()
                .increment(attemptKey)
                .flatMap(attempts -> {
                    // Mirror to local cache
                    localAttempts.put(key, attempts.intValue());

                    if (attempts == 1) {
                        return redisTemplate.expire(attemptKey, attemptWindow)
                                .thenReturn(attempts.intValue());
                    }
                    
                    if (attempts >= maxAttempts) {
                        int lockoutMultiplier = (int) Math.min(attempts - maxAttempts + 1, 6);
                        Duration lockoutTime = progressiveLockoutBase.multipliedBy(lockoutMultiplier);
                        
                        log.warn("Account locked due to {} failed attempts: {} (IP: {})", attempts, key, clientIp);

                        // Mirror lockout to local cache
                        localLockouts.put(key, Instant.now().plus(lockoutTime));
                        
                        return redisTemplate.opsForValue()
                                .set(LOCKOUT_PREFIX + key, String.valueOf(attempts), lockoutTime)
                                .then(sendLockoutNotificationOnce(key, attempts.intValue(), lockoutTime.toMinutes(), clientIp))
                                .thenReturn(attempts.intValue());
                    }
                    
                    return Mono.just(attempts.intValue());
                })
                .onErrorResume(e -> {
                    log.warn("Redis unavailable for recording attempt, falling back to Caffeine: {}", e.getMessage());
                    return Mono.fromCallable(() -> recordFailedAttemptLocally(key, clientIp));
                });
    }

    /**
     * Get current number of failed attempts.
     */
    public Mono<Integer> getFailedAttempts(String key) {
        return redisTemplate.opsForValue()
                .get(LOGIN_ATTEMPT_PREFIX + key)
                .map(Integer::parseInt)
                .defaultIfEmpty(0)
                .onErrorResume(e -> {
                    Integer local = localAttempts.getIfPresent(key);
                    return Mono.just(local != null ? local : 0);
                });
    }

    /**
     * Clear failed attempts after successful login.
     */
    public Mono<Void> clearFailedAttempts(String key) {
        // Always clear local caches
        localAttempts.invalidate(key);
        localLockouts.invalidate(key);

        return redisTemplate.delete(LOGIN_ATTEMPT_PREFIX + key)
                .then(redisTemplate.delete(LOCKOUT_PREFIX + key))
                .then()
                .onErrorResume(e -> {
                    log.warn("Failed to clear login attempts in Redis (local caches cleared): {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Get remaining attempts before lockout.
     */
    public Mono<Integer> getRemainingAttempts(String key) {
        return getFailedAttempts(key)
                .map(attempts -> Math.max(0, maxAttempts - attempts));
    }

    // ==================== Caffeine Fallback Methods ====================

    private boolean isBlockedLocally(String key) {
        Instant lockoutUntil = localLockouts.getIfPresent(key);
        if (lockoutUntil == null) return false;
        if (Instant.now().isAfter(lockoutUntil)) {
            localLockouts.invalidate(key);
            return false;
        }
        return true;
    }

    private long getRemainingLockoutTimeLocally(String key) {
        Instant lockoutUntil = localLockouts.getIfPresent(key);
        if (lockoutUntil == null) return 0L;
        long remaining = Duration.between(Instant.now(), lockoutUntil).getSeconds();
        return Math.max(0L, remaining);
    }

    private int recordFailedAttemptLocally(String key, String clientIp) {
        Integer current = localAttempts.getIfPresent(key);
        int attempts = (current != null ? current : 0) + 1;
        localAttempts.put(key, attempts);

        if (attempts >= maxAttempts) {
            int lockoutMultiplier = (int) Math.min(attempts - maxAttempts + 1, 6);
            Duration lockoutTime = progressiveLockoutBase.multipliedBy(lockoutMultiplier);
            localLockouts.put(key, Instant.now().plus(lockoutTime));
            log.warn("Account locked (Caffeine fallback) due to {} failed attempts: {} (IP: {})", attempts, key, clientIp);
        }

        return attempts;
    }

    // ==================== Notification ====================

    /**
     * Send lockout notification only once per lockout period.
     */
    private Mono<Void> sendLockoutNotificationOnce(String email, int attempts, long lockoutMinutes, String clientIp) {
        String notifiedKey = LOCKOUT_NOTIFIED_PREFIX + email;
        
        return redisTemplate.opsForValue()
                .setIfAbsent(notifiedKey, "1", Duration.ofMinutes(lockoutMinutes))
                .flatMap(wasSet -> {
                    if (Boolean.TRUE.equals(wasSet)) {
                        return userRepository.findByEmail(email)
                                .flatMap(user -> emailService.sendAccountLockoutNotification(
                                        user.getEmail(),
                                        user.getName(),
                                        attempts,
                                        lockoutMinutes,
                                        clientIp
                                ))
                                .onErrorResume(e -> {
                                    log.warn("Failed to send lockout notification: {}", e.getMessage());
                                    return Mono.empty();
                                });
                    }
                    return Mono.empty();
                });
    }
}
