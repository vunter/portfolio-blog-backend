package dev.catananti.service;

import dev.catananti.entity.EmailChangeToken;
import dev.catananti.repository.EmailChangeTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

    private static final Duration TOKEN_VALIDITY = Duration.ofHours(1);
    private static final Duration REVERT_TOKEN_VALIDITY = Duration.ofHours(48);
    private static final int MAX_TOKENS_PER_HOUR = 3;
    private static final int TOKEN_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${app.site-url:http://localhost:4200}")
    private String siteUrl;

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
        return tokenRepository.countRecentTokensByUserId(userId, oneHourAgo)
                .flatMap(count -> {
                    if (count >= MAX_TOKENS_PER_HOUR) {
                        log.warn("Email change rate limit exceeded for userId: {}", userId);
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
                            .expiresAt(LocalDateTime.now().plus(TOKEN_VALIDITY))
                            .used(false)
                            .createdAt(LocalDateTime.now())
                            .build();

                    return tokenRepository.save(token)
                            .flatMap(saved -> emailService.sendEmailChangeVerification(
                                    newEmail, userName, plainToken)
                                    .doOnSuccess(v -> log.info("Email change verification sent to: {} for userId: {}", newEmail, userId))
                                    .onErrorResume(e -> {
                                        log.warn("Email send failed for email change (userId: {}), token saved for retry: {}", userId, e.getMessage(), e);
                                        return Mono.empty();
                                    }));
                });
    }

    /**
     * Verify an email change token and apply the email update.
     */
    @Transactional
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
    @Transactional
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
