package dev.catananti.service;

import dev.catananti.entity.PasswordResetToken;
import dev.catananti.repository.PasswordResetTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import dev.catananti.util.PiiMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Service for handling password reset functionality with security best practices.
 */
@Service
@Slf4j
public class PasswordResetService {

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final IdService idService;
    private final RefreshTokenService refreshTokenService;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final dev.catananti.scheduler.SchedulerLock schedulerLock;

    private static final int TOKEN_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String RATE_LIMIT_PREFIX = "pwd_reset_rate:";

    @Value("${app.url:http://localhost:8080}")
    private String appUrl;

    @Value("${app.password-reset.token-validity-hours:1}")
    private int tokenValidityHours;

    @Value("${app.password-reset.max-tokens-per-hour:3}")
    private int maxTokensPerHour;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                 UserRepository userRepository,
                                 EmailService emailService,
                                 PasswordEncoder passwordEncoder,
                                 AuditService auditService,
                                 IdService idService,
                                 RefreshTokenService refreshTokenService,
                                 @Qualifier("reactiveRedisTemplate") @Nullable ReactiveRedisTemplate<String, String> redisTemplate,
                                 dev.catananti.scheduler.SchedulerLock schedulerLock) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.idService = idService;
        this.refreshTokenService = refreshTokenService;
        this.redisTemplate = redisTemplate;
        this.schedulerLock = schedulerLock;
    }

    /**
     * Request a password reset. Always returns success to prevent email enumeration.
     */
    @Transactional
    public Mono<Void> requestPasswordReset(String email) {
        String normalizedEmail = email.toLowerCase().trim();
        // F-200: Per-email rate limiting via Redis
        Mono<Boolean> rateLimitCheck = checkEmailRateLimit(normalizedEmail);

        return rateLimitCheck.flatMap(allowed -> {
            if (!allowed) {
                log.warn("Password reset rate limit exceeded for email: {}", PiiMasker.maskEmail(normalizedEmail));
                return Mono.empty();
            }
            return userRepository.findByEmail(normalizedEmail)
                    .flatMap(user -> {
                        // Check rate limiting: max 3 tokens per hour per user
                        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
                        return tokenRepository.countRecentTokensByUserId(user.getId(), oneHourAgo)
                                .flatMap(count -> {
                                    if (count >= maxTokensPerHour) {
                                        log.warn("Password reset rate limit exceeded for: {}", PiiMasker.maskEmail(email));
                                        return Mono.empty(); // Silently ignore
                                    }

                                    // Generate secure token
                                    String plainToken = generateSecureToken();
                                    // SEC-05: Store SHA-256 hash of the token in the database
                                    String hashedToken = hashToken(plainToken);

                                    PasswordResetToken resetToken = PasswordResetToken.builder()
                                            .id(idService.nextId())
                                            .userId(user.getId())
                                            .token(hashedToken)
                                            .expiresAt(LocalDateTime.now().plus(Duration.ofHours(tokenValidityHours)))
                                            .used(false)
                                            .createdAt(LocalDateTime.now())
                                            .build();

                                    return tokenRepository.save(resetToken)
                                            .flatMap(saved -> emailService.sendPasswordResetEmail(
                                                    user.getEmail(),
                                                    user.getName(),
                                                    plainToken // Send plain token in email
                                            ))
                                            .doOnSuccess(v -> log.debug("Password reset email sent to: {}", PiiMasker.maskEmail(email)))
                                            .doOnError(e -> log.error("Failed to process password reset for {}: {}", PiiMasker.maskEmail(email), e.getMessage(), e));
                                });
                    })
                    .onErrorResume(e -> {
                        log.warn("Password reset error for {}: {}", PiiMasker.maskEmail(email), e.getMessage(), e);
                        return Mono.empty();
                    })
                    .then();
        });
    }

    /**
     * F-200: Check per-email rate limit using Redis INCR + EXPIRE pattern.
     * Allows max 3 requests per email per hour. Falls back to allow if Redis unavailable.
     */
    private Mono<Boolean> checkEmailRateLimit(String email) {
        if (redisTemplate == null) return Mono.just(true);
        String key = RATE_LIMIT_PREFIX + email;
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        return redisTemplate.expire(key, Duration.ofHours(1)).thenReturn(count);
                    }
                    return Mono.just(count);
                })
                .map(count -> count <= maxTokensPerHour)
                .onErrorReturn(true);
    }

    /**
     * Validate a password reset token.
     * SEC: Artificial random delay prevents timing-based token enumeration.
     */
    public Mono<Boolean> validateToken(String token) {
        // SEC-05: Hash the incoming token before lookup
        return tokenRepository.findByTokenAndUsedFalse(hashToken(token))
                .map(PasswordResetToken::isValid)
                .delayElement(Duration.ofMillis(50 + SECURE_RANDOM.nextInt(100)))
                .defaultIfEmpty(false);
    }

    /**
     * Reset password using a valid token.
     */
    // Password complexity regex — must match RegisterRequest validation
    private static final java.util.regex.Pattern PASSWORD_PATTERN = java.util.regex.Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,}$"
    );

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Mono<Void> resetPassword(String token, String newPassword) {
        // VAL-04: Validate password strength (aligned with RegisterRequest policy)
        if (newPassword == null || newPassword.length() < 12) {
            return Mono.error(new IllegalArgumentException("error.password_too_short"));
        }
        if (newPassword.length() > 128) {
            return Mono.error(new IllegalArgumentException("error.password_too_long"));
        }
        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            return Mono.error(new IllegalArgumentException("error.password_too_weak"));
        }
        // SEC-05: Hash the incoming token before lookup
        // SEC: Artificial random delay prevents timing-based token enumeration
        return tokenRepository.findByTokenAndUsedFalse(hashToken(token))
                .filter(PasswordResetToken::isValid)
                .delayElement(Duration.ofMillis(50 + SECURE_RANDOM.nextInt(100)))
                .switchIfEmpty(Mono.error(new SecurityException("error.invalid_reset_token")))
                // SEC: Atomically mark token as used FIRST to prevent concurrent use
                .flatMap(resetToken -> tokenRepository.markAsUsedConditionally(resetToken.getId(), LocalDateTime.now())
                        .flatMap(rowsAffected -> {
                            if (rowsAffected == 0) {
                                return Mono.<Void>error(new SecurityException("error.invalid_reset_token"));
                            }
                            return userRepository.findById(resetToken.getUserId())
                                    .switchIfEmpty(Mono.error(new SecurityException("error.user_not_found")))
                                    .flatMap(user -> {
                                        // F-ASYNC-04: Offload BCrypt hashing (~200ms) from reactive thread
                                        return Mono.fromCallable(() -> passwordEncoder.encode(newPassword))
                                                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                                                .flatMap(encodedPassword -> {
                                                    user.setPasswordHash(encodedPassword);
                                                    user.setUpdatedAt(LocalDateTime.now());

                                                    return userRepository.save(user)
                                                            .then(refreshTokenService.revokeAllUserTokens(user.getId()))
                                                            .then(auditService.logPasswordReset(user.getId(), user.getEmail()))
                                                            .doOnSuccess(v -> log.debug("Password reset completed for: {}", PiiMasker.maskEmail(user.getEmail())))
                                                            // Email notification sent outside core transaction — failure doesn't roll back password change
                                                            .then(Mono.defer(() -> emailService.sendPasswordChangedNotification(user.getEmail(), user.getName())
                                                                    .onErrorResume(e -> {
                                                                        log.warn("Failed to send password changed notification to {}: {}", PiiMasker.maskEmail(user.getEmail()), e.getMessage(), e);
                                                                        return Mono.empty();
                                                                    })));
                                                })
                                                // SEC: If password change fails, un-mark the token so it can be reused
                                                .onErrorResume(e -> tokenRepository.unmarkAsUsed(resetToken.getId())
                                                        .then(Mono.error(e)));
                                    });
                        }));
    }

    /**
     * Generate a cryptographically secure token.
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[TOKEN_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SEC-05: Hash a token with SHA-256 for secure storage.
     */
    private String hashToken(String token) {
        return DigestUtils.sha256Hex(token);
    }

    public Mono<Void> cleanupExpiredTokens() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(1);
        return schedulerLock.executeWithLock("password-reset-cleanup", Duration.ofMinutes(5),
                tokenRepository.deleteExpiredTokens(cutoff)
                        .timeout(Duration.ofSeconds(30))
                        .doOnSuccess(count -> log.info("Cleaned up expired password reset tokens"))
                        .doOnError(e -> log.error("Failed to cleanup expired password reset tokens: {}", e.getMessage(), e))
                        .onErrorComplete()
                        .then()
        );
    }

    /**
     * Cleanup expired tokens (runs every 6 hours by default).
     */
    @Scheduled(fixedRateString = "${scheduling.password-reset-cleanup-ms:21600000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void cleanupExpiredTokensScheduled() {
        cleanupExpiredTokens().subscribe();
    }
}
