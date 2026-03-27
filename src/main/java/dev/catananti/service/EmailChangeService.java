package dev.catananti.service;

import dev.catananti.entity.EmailChangeToken;
import dev.catananti.repository.EmailChangeTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import dev.catananti.util.PiiMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Service for handling email change verification via magic link.
 * When a user requests an email change, a token is generated and sent to the new address.
 * The email is only updated after the user clicks the verification link.
 */
@Service
@Slf4j
public class EmailChangeService {

    private final EmailChangeTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final IdService idService;
    private final AuditService auditService;

    private static final Duration REVERT_TOKEN_VALIDITY = Duration.ofHours(48);
    private static final int TOKEN_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${app.site-url:http://localhost:4200}")
    private String siteUrl;

    @Value("${app.email-change.token-validity-hours:1}")
    private int tokenValidityHours;

    @Value("${app.email-change.max-tokens-per-hour:3}")
    private int maxTokensPerHour;

    @Value("${app.email-change.max-tokens-per-target-email-per-hour:5}")
    private int maxTokensPerTargetEmailPerHour;

    public EmailChangeService(EmailChangeTokenRepository tokenRepository,
                               UserRepository userRepository,
                               EmailService emailService,
                               IdService idService,
                               AuditService auditService) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.idService = idService;
        this.auditService = auditService;
    }

    /**
     * Initiate an email change. Creates a verification token and sends
     * a magic link to the NEW email address.
     */
    @Transactional
    public Mono<Void> initiateEmailChange(Long userId, String newEmail, String userName) {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        String normalizedNewEmail = newEmail.toLowerCase().trim();

        // Check both per-user and per-target-email rate limits
        return tokenRepository.countRecentTokensByUserId(userId, oneHourAgo)
                .zipWith(tokenRepository.countRecentTokensByNewEmail(normalizedNewEmail, oneHourAgo))
                .flatMap(counts -> {
                    long userCount = counts.getT1();
                    long targetEmailCount = counts.getT2();

                    if (userCount >= maxTokensPerHour) {
                        log.warn("Email change rate limit exceeded for userId: {}", userId);
                        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "error.email_change_rate_limit"));
                    }
                    if (targetEmailCount >= maxTokensPerTargetEmailPerHour) {
                        log.warn("Email change rate limit exceeded for target email: {}", PiiMasker.maskEmail(normalizedNewEmail));
                        return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "error.email_change_rate_limit"));
                    }

                    String plainToken = generateSecureToken();
                    String hashedToken = DigestUtils.sha256Hex(plainToken);

                    EmailChangeToken token = EmailChangeToken.builder()
                            .id(idService.nextId())
                            .userId(userId)
                            .newEmail(newEmail)
                            .token(hashedToken)
                            .expiresAt(LocalDateTime.now().plus(Duration.ofHours(tokenValidityHours)))
                            .used(false)
                            .createdAt(LocalDateTime.now())
                            .build();

                    return tokenRepository.save(token)
                            .flatMap(saved -> emailService.sendEmailChangeVerification(
                                    newEmail, userName, plainToken)
                                    .doOnSuccess(v -> log.info("Email change verification sent to: {} for userId: {}", PiiMasker.maskEmail(newEmail), userId))
                                    .onErrorResume(e -> {
                                        log.warn("Email send failed for email change (userId: {}), token saved for retry: {}", userId, e.getMessage(), e);
                                        return Mono.empty();
                                    }));
                });
    }

    /**
     * Verify an email change token and apply the email update.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Mono<String> verifyEmailChange(String plainToken) {
        String hashedToken = DigestUtils.sha256Hex(plainToken);
        return tokenRepository.findByTokenAndUsedFalse(hashedToken)
                .filter(EmailChangeToken::isValid)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "error.invalid_or_expired_token")))
                .flatMap(token -> userRepository.findById(token.getUserId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "error.user_not_found")))
                        .flatMap(user -> {
                            // Check the new email is still available
                            return userRepository.existsByEmail(token.getNewEmail())
                                    .flatMap(exists -> {
                                        if (exists) {
                                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                                    "error.email_in_use"));
                                        }

                                        String oldEmail = user.getEmail();
                                        user.setEmail(token.getNewEmail());
                                        user.setUpdatedAt(LocalDateTime.now());

                                        // Create revert token (48h validity)
                                        String revertPlainToken = generateSecureToken();
                                        String revertHashedToken = DigestUtils.sha256Hex(revertPlainToken);
                                        EmailChangeToken revertToken = EmailChangeToken.builder()
                                                .id(idService.nextId())
                                                .userId(user.getId())
                                                .newEmail(oldEmail)
                                                .oldEmail(token.getNewEmail())
                                                .token(revertHashedToken)
                                                .expiresAt(LocalDateTime.now().plus(REVERT_TOKEN_VALIDITY))
                                                .used(false)
                                                .createdAt(LocalDateTime.now())
                                                .build();

                                        String revertUrl = siteUrl + "/auth/revert-email-change?token=" + revertPlainToken;

                                        return userRepository.save(user)
                                                .then(tokenRepository.markAsUsed(token.getId(), LocalDateTime.now()))
                                                .then(tokenRepository.save(revertToken))
                                                .then(auditService.logEmailChange(user.getId(), oldEmail, token.getNewEmail()))
                                                .then(emailService.sendEmailChangedNotification(oldEmail, user.getName(), token.getNewEmail(), revertUrl)
                                                        .onErrorResume(e -> {
                                                            log.warn("Failed to send email changed notification for userId={}: {}", user.getId(), e.getMessage(), e);
                                                            return Mono.empty();
                                                        }))
                                                .thenReturn(token.getNewEmail());
                                    });
                        }));
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[TOKEN_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Revert an email change using a revert token sent to the old email address.
     * Only tokens with oldEmail set (revert tokens) are accepted.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Mono<String> revertEmailChange(String plainToken) {
        String hashedToken = DigestUtils.sha256Hex(plainToken);
        return tokenRepository.findByTokenAndUsedFalse(hashedToken)
                .filter(EmailChangeToken::isValid)
                .filter(token -> token.getOldEmail() != null)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "error.invalid_or_expired_token")))
                .flatMap(token -> userRepository.findById(token.getUserId())
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "error.user_not_found")))
                        .flatMap(user -> {
                            String revertedFromEmail = user.getEmail();
                            String restoredEmail = token.getNewEmail();

                            user.setEmail(restoredEmail);
                            user.setUpdatedAt(LocalDateTime.now());

                            return userRepository.save(user)
                                    .then(tokenRepository.markAsUsed(token.getId(), LocalDateTime.now()))
                                    .then(auditService.logEmailRevert(user.getId(), revertedFromEmail, restoredEmail))
                                    .then(emailService.sendEmailRevertedNotification(revertedFromEmail, user.getName(), restoredEmail)
                                            .onErrorResume(e -> {
                                                log.warn("Failed to send email reverted notification for userId={}: {}", user.getId(), e.getMessage(), e);
                                                return Mono.empty();
                                            }))
                                    .thenReturn(restoredEmail);
                        }));
    }
}
