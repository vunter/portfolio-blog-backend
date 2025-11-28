package dev.catananti.service;

import dev.catananti.entity.PasswordResetToken;
import dev.catananti.repository.PasswordResetTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * TODO F-200: Add rate limiting per email address for password reset requests (not just global)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final IdService idService;

    private static final Duration TOKEN_VALIDITY = Duration.ofHours(1);
    private static final int MAX_TOKENS_PER_HOUR = 3;
    private static final int TOKEN_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${app.url:http://localhost:8080}")
    private String appUrl;

    /**
     * Request a password reset. Always returns success to prevent email enumeration.
     */
    @Transactional
    public Mono<Void> requestPasswordReset(String email) {
        // Always return success to prevent email enumeration attacks
        return userRepository.findByEmail(email.toLowerCase().trim())
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
