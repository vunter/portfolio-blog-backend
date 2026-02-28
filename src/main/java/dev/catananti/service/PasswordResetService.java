package dev.catananti.service;

import dev.catananti.entity.PasswordResetToken;
import dev.catananti.repository.PasswordResetTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
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
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    private static final Duration TOKEN_VALIDITY = Duration.ofHours(1);
    private static final int MAX_TOKENS_PER_HOUR = 3;
    private static final int TOKEN_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String RATE_LIMIT_PREFIX = "pwd_reset_rate:";

    @Value("${app.url:http://localhost:8080}")
    private String appUrl;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                 UserRepository userRepository,
                                 EmailService emailService,
                                 PasswordEncoder passwordEncoder,
                                 AuditService auditService,
                                 IdService idService,
                                 @Qualifier("reactiveRedisTemplate") @Nullable ReactiveRedisTemplate<String, String> redisTemplate) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.idService = idService;
        this.redisTemplate = redisTemplate;
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
                log.warn("Password reset rate limit exceeded for email: {}", normalizedEmail);
                return Mono.empty();
            }
            return userRepository.findByEmail(normalizedEmail)
                    .flatMap(user -> {
                        // Check rate limiting: max 3 tokens per hour per user
                        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
                        return tokenRepository.countRecentTokensByUserId(user.getId(), oneHourAgo)
                                .flatMap(count -> {
                                    if (count >= MAX_TOKENS_PER_HOUR) {
                                        log.warn("Password reset rate limit exceeded for: {}", email);
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
                                            .expiresAt(LocalDateTime.now().plus(TOKEN_VALIDITY))
                                            .used(false)
                                            .createdAt(LocalDateTime.now())
                                            .build();

                                    return tokenRepository.save(resetToken)
                                            .flatMap(saved -> emailService.sendPasswordResetEmail(
                                                    user.getEmail(),
                                                    user.getName(),
                                                    plainToken // Send plain token in email
                                            ))
                                            .doOnSuccess(v -> log.debug("Password reset email sent to: {}", email))
                                            .doOnError(e -> log.error("Failed to process password reset for {}: {}", email, e.getMessage()));
                                });
                    })
                    .onErrorResume(e -> {
                        log.warn("Password reset error for {}: {}", email, e.getMessage());
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
                .map(count -> count <= MAX_TOKENS_PER_HOUR)
                .onErrorReturn(true);
    }

    /**
     * Validate a password reset token.
     */
    public Mono<Boolean> validateToken(String token) {
        // SEC-05: Hash the incoming token before lookup
        return tokenRepository.findByTokenAndUsedFalse(hashToken(token))
                .map(PasswordResetToken::isValid)
                .defaultIfEmpty(false);
    }

    /**
     * Reset password using a valid token.
     */
    // Password complexity regex — must match RegisterRequest validation
    private static final java.util.regex.Pattern PASSWORD_PATTERN = java.util.regex.Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,}$"
    );

    @Transactional
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
        return tokenRepository.findByTokenAndUsedFalse(hashToken(token))
                .filter(PasswordResetToken::isValid)
                .switchIfEmpty(Mono.error(new SecurityException("error.invalid_reset_token")))
                .flatMap(resetToken -> userRepository.findById(resetToken.getUserId())
                        .switchIfEmpty(Mono.error(new SecurityException("error.user_not_found")))
                        .flatMap(user -> {
                            // Update password
                            user.setPasswordHash(passwordEncoder.encode(newPassword));
                            user.setUpdatedAt(LocalDateTime.now());

                            return userRepository.save(user)
                                    .then(tokenRepository.markAsUsed(resetToken.getId(), LocalDateTime.now()))
                                    .then(auditService.logPasswordReset(user.getId(), user.getEmail()))
                                    .doOnSuccess(v -> log.debug("Password reset completed for: {}", user.getEmail()))
                                    // Email notification sent outside core transaction — failure doesn't roll back password change
                                    .then(Mono.defer(() -> emailService.sendPasswordChangedNotification(user.getEmail(), user.getName())
                                            .onErrorResume(e -> {
                                                log.warn("Failed to send password changed notification to {}: {}", user.getEmail(), e.getMessage());
                                                return Mono.empty();
                                            })));
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

    /**
     * Cleanup expired tokens (runs every 6 hours by default).
     * Uses .subscribe() to avoid blocking the scheduler thread.
     */
    @Scheduled(fixedRateString = "${scheduling.password-reset-cleanup-ms:21600000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void cleanupExpiredTokens() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(1);
        tokenRepository.deleteExpiredTokens(cutoff)
                .subscribe(
                        result -> log.info("Cleaned up expired password reset tokens"),
                        error -> log.error("Failed to cleanup expired password reset tokens: {}", error.getMessage())
                );
    }
}
