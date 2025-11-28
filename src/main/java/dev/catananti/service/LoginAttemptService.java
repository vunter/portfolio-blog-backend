package dev.catananti.service;

import dev.catananti.config.ResilienceConfig;
import dev.catananti.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Service to track and limit login attempts to prevent brute force attacks.
 * Only active when Redis is available (production).
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
    }

    /**
     * Check if the user/IP is currently locked out.
     */
    // TODO F-185: Add local Caffeine fallback for fail-open scenario when Redis is unavailable
    public Mono<Boolean> isBlocked(String key) {
        return redisTemplate.hasKey(LOCKOUT_PREFIX + key)
                .timeout(resilience.getRedisTimeout())
                .onErrorReturn(false);
    }

    /**
     * Get remaining lockout time in seconds.
     */
    public Mono<Long> getRemainingLockoutTime(String key) {
        return redisTemplate.getExpire(LOCKOUT_PREFIX + key)
                .map(Duration::getSeconds)
                .defaultIfEmpty(0L)
                .timeout(resilience.getRedisTimeout())
                .onErrorReturn(0L);
    }

    /**
     * Record a failed login attempt (without IP tracking).
     */
    public Mono<Integer> recordFailedAttempt(String key) {
        return recordFailedAttempt(key, null);
    }

    /**
     * Record a failed login attempt with optional IP address for notifications.
     */
    public Mono<Integer> recordFailedAttempt(String key, String clientIp) {
        String attemptKey = LOGIN_ATTEMPT_PREFIX + key;
        
        return redisTemplate.opsForValue()
                .increment(attemptKey)
                .flatMap(attempts -> {
                    if (attempts == 1) {
                        // First attempt, set expiry
                        return redisTemplate.expire(attemptKey, attemptWindow)
                                .thenReturn(attempts.intValue());
                    }
                    
                    if (attempts >= maxAttempts) {
                        // Lock out the user with progressive duration
                        int lockoutMultiplier = (int) Math.min(attempts - maxAttempts + 1, 6);
                        Duration lockoutTime = progressiveLockoutBase.multipliedBy(lockoutMultiplier);
                        
                        log.warn("Account locked due to {} failed attempts: {} (IP: {})", attempts, key, clientIp);
                        
                        return redisTemplate.opsForValue()
                                .set(LOCKOUT_PREFIX + key, String.valueOf(attempts), lockoutTime)
                                .then(sendLockoutNotificationOnce(key, attempts.intValue(), lockoutTime.toMinutes(), clientIp))
                                .thenReturn(attempts.intValue());
                    }
                    
                    return Mono.just(attempts.intValue());
                })
                .onErrorResume(e -> {
                    log.warn("Failed to record login attempt: {}", e.getMessage());
                    return Mono.just(0);
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
                .onErrorReturn(0);
    }

    /**
     * Clear failed attempts after successful login.
     */
    public Mono<Void> clearFailedAttempts(String key) {
        return redisTemplate.delete(LOGIN_ATTEMPT_PREFIX + key)
                .then(redisTemplate.delete(LOCKOUT_PREFIX + key))
                .then()
                .onErrorResume(e -> {
                    log.warn("Failed to clear login attempts: {}", e.getMessage());
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

    /**
     * Send lockout notification only once per lockout period.
     */
    private Mono<Void> sendLockoutNotificationOnce(String email, int attempts, long lockoutMinutes, String clientIp) {
        String notifiedKey = LOCKOUT_NOTIFIED_PREFIX + email;
        
        // Only notify once per lockout period
        return redisTemplate.opsForValue()
                .setIfAbsent(notifiedKey, "1", Duration.ofMinutes(lockoutMinutes))
                .flatMap(wasSet -> {
                    if (Boolean.TRUE.equals(wasSet)) {
                        // First time notifying for this lockout
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
