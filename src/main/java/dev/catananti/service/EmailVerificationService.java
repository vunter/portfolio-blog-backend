package dev.catananti.service;

import dev.catananti.entity.EmailVerificationToken;
import dev.catananti.repository.EmailVerificationTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Proof-of-ownership verification for the email address given at registration.
 *
 * <p>Mirrors {@link EmailChangeService}: a random 32-byte token is emailed in
 * plain text while only its SHA-256 is stored, and consumption happens through
 * a conditional UPDATE so concurrent requests cannot both verify.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final IdService idService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int TOKEN_BYTES = 32;

    @Value("${app.email-verification.token-validity-hours:24}")
    private int tokenValidityHours;

    @Value("${app.email-verification.max-tokens-per-hour:3}")
    private int maxTokensPerHour;

    /**
     * Issues and emails a verification token for the given address.
     *
     * <p>Silent when the email does not exist or is already verified: the
     * response is identical in both cases so the endpoint cannot be used as an
     * oracle of which addresses have an account.
     */
    public Mono<Void> sendVerification(String email) {
        String normalized = email == null ? "" : email.strip().toLowerCase();

        return userRepository.findByEmail(normalized)
                .filter(user -> !Boolean.TRUE.equals(user.getEmailVerified()))
                .flatMap(user -> issueAndSend(user.getId(), normalized, user.getName()))
                .then();
    }

    /**
     * Consumes the token. Returns the user id.
     *
     * <p>Consumption is a conditional UPDATE returning a row count: whoever gets
     * 0 lost the race to another request and must not verify. An
     * {@code if (token.isUsed())} in Java would pass the test and fail under
     * concurrency.
     */
    public Mono<Long> verify(String plainToken) {
        String hashed = DigestUtils.sha256Hex(plainToken);

        return tokenRepository.findByTokenAndUsedFalse(hashed)
                .filter(EmailVerificationToken::isValid)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "error.invalid_or_expired_token")))
                .flatMap(token -> tokenRepository.consumeIfUnused(token.getId(), LocalDateTime.now())
                        .flatMap(rows -> {
                            if (rows == 0) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "error.invalid_or_expired_token"));
                            }
                            return Mono.just(token.getUserId());
                        }))
                .doOnSuccess(userId -> log.info("Email verified for userId: {}", userId));
    }

    private Mono<Void> issueAndSend(Long userId, String email, String name) {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);

        return tokenRepository.countRecentByUserId(userId, oneHourAgo)
                .flatMap(recent -> {
                    if (recent >= maxTokensPerHour) {
                        log.warn("Email verification rate limit hit for userId: {}", userId);
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS, "error.verification_rate_limit"));
                    }

                    byte[] raw = new byte[TOKEN_BYTES];
                    SECURE_RANDOM.nextBytes(raw);
                    String plainToken = ENCODER.encodeToString(raw);

                    EmailVerificationToken token = EmailVerificationToken.builder()
                            .id(idService.nextId())
                            .userId(userId)
                            .email(email)
                            .token(DigestUtils.sha256Hex(plainToken))
                            .expiresAt(LocalDateTime.now().plus(Duration.ofHours(tokenValidityHours)))
                            .used(false)
                            .createdAt(LocalDateTime.now())
                            .build();

                    return tokenRepository.save(token)
                            .flatMap(saved -> emailService.sendEmailVerification(email, name, plainToken))
                            // The token stays saved for resend. Propagating the error
                            // would take the registration down over an SMTP failure.
                            .onErrorResume(e -> {
                                log.warn("Verification email failed for {} (token kept for resend): {}",
                                        PiiMasker.maskEmail(email), e.getMessage());
                                return Mono.empty();
                            });
                })
                .then();
    }
}
