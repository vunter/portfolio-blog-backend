package dev.catananti.service;

import dev.catananti.entity.EmailVerificationToken;
import dev.catananti.entity.User;
import dev.catananti.repository.EmailVerificationTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock private EmailVerificationTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private IdService idService;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(tokenRepository, userRepository, emailService, idService);
        ReflectionTestUtils.setField(service, "tokenValidityHours", 24);
        ReflectionTestUtils.setField(service, "maxTokensPerHour", 3);
    }

    private User user(boolean verified) {
        return User.builder()
                .id(10L)
                .email("user@test.dev")
                .name("Ana")
                .emailVerified(verified)
                .build();
    }

    @Test
    @DisplayName("should store the hashed token and email the plain one")
    void sendVerificationStoresHashedTokenAndEmailsPlainOne() {
        when(userRepository.findByEmail("user@test.dev")).thenReturn(Mono.just(user(false)));
        when(idService.nextId()).thenReturn(1L);
        when(tokenRepository.countRecentByUserId(eq(10L), any())).thenReturn(Mono.just(0L));
        when(tokenRepository.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(emailService.sendEmailVerification(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.sendVerification("user@test.dev")).verifyComplete();

        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(saved.capture());
        ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmailVerification(eq("user@test.dev"), eq("Ana"), mailed.capture());

        // the database keeps the hash, the email carries the plain token
        assertThat(saved.getValue().getToken()).isNotEqualTo(mailed.getValue());
        assertThat(saved.getValue().getToken()).isEqualTo(DigestUtils.sha256Hex(mailed.getValue()));
    }

    @Test
    @DisplayName("should reject with 429 when the hourly rate limit is reached")
    void sendVerificationRejectsWhenRateLimitReached() {
        when(userRepository.findByEmail("user@test.dev")).thenReturn(Mono.just(user(false)));
        when(tokenRepository.countRecentByUserId(eq(10L), any())).thenReturn(Mono.just(3L));

        StepVerifier.create(service.sendVerification("user@test.dev"))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                .verify();

        verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("should be a no-op when the address is already verified")
    void sendVerificationIsNoOpWhenAlreadyVerified() {
        // OAuth2 accounts are born verified; resending would be noise and a cheap
        // spam vector against someone else's address.
        when(userRepository.findByEmail("user@test.dev")).thenReturn(Mono.just(user(true)));

        StepVerifier.create(service.sendVerification("user@test.dev")).verifyComplete();

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendEmailVerification(any(), any(), any());
    }

    @Test
    @DisplayName("should complete silently for an unknown address")
    void sendVerificationIsSilentForUnknownEmail() {
        // Never reveal whether the address exists: the response is identical either way.
        when(userRepository.findByEmail("nobody@test.dev")).thenReturn(Mono.empty());

        StepVerifier.create(service.sendVerification("nobody@test.dev")).verifyComplete();

        verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("should consume the token and return the userId")
    void verifyConsumesTokenAndReturnsUserId() {
        String plain = "tok-abc";
        EmailVerificationToken token = EmailVerificationToken.builder()
                .id(5L).userId(10L).email("user@test.dev")
                .token(DigestUtils.sha256Hex(plain))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .used(false).build();

        when(tokenRepository.findByTokenAndUsedFalse(DigestUtils.sha256Hex(plain))).thenReturn(Mono.just(token));
        when(tokenRepository.consumeIfUnused(eq(5L), any())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.verify(plain)).expectNext(10L).verifyComplete();
        verify(tokenRepository).consumeIfUnused(eq(5L), any());
    }

    @Test
    @DisplayName("should reject a token already consumed by a concurrent request")
    void verifyRejectsTokenAlreadyConsumedByAConcurrentRequest() {
        // Two tabs opening the same link: the race loser gets 0 rows back
        // and must not verify again.
        String plain = "tok-abc";
        EmailVerificationToken token = EmailVerificationToken.builder()
                .id(5L).userId(10L).email("user@test.dev")
                .token(DigestUtils.sha256Hex(plain))
                .expiresAt(LocalDateTime.now().plusHours(1))
                .used(false).build();

        when(tokenRepository.findByTokenAndUsedFalse(any())).thenReturn(Mono.just(token));
        when(tokenRepository.consumeIfUnused(eq(5L), any())).thenReturn(Mono.just(0L));

        StepVerifier.create(service.verify(plain))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .verify();
    }

    @Test
    @DisplayName("should reject an expired token without consuming it")
    void verifyRejectsExpiredToken() {
        EmailVerificationToken expired = EmailVerificationToken.builder()
                .id(5L).userId(10L).token(DigestUtils.sha256Hex("t"))
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .used(false).build();
        when(tokenRepository.findByTokenAndUsedFalse(any())).thenReturn(Mono.just(expired));

        StepVerifier.create(service.verify("t"))
                .expectError(ResponseStatusException.class)
                .verify();

        verify(tokenRepository, never()).consumeIfUnused(any(), any());
    }

    @Test
    @DisplayName("should reject an unknown token")
    void verifyRejectsUnknownToken() {
        when(tokenRepository.findByTokenAndUsedFalse(any())).thenReturn(Mono.empty());
        StepVerifier.create(service.verify("nope"))
                .expectError(ResponseStatusException.class)
                .verify();
    }

    @Test
    @DisplayName("should complete even when SMTP fails, keeping the token for resend")
    void sendVerificationSurvivesSmtpFailure() {
        // The token stays saved for resend; an SMTP failure must not propagate,
        // or it would take the registration down with it.
        when(userRepository.findByEmail("user@test.dev")).thenReturn(Mono.just(user(false)));
        when(idService.nextId()).thenReturn(1L);
        when(tokenRepository.countRecentByUserId(eq(10L), any())).thenReturn(Mono.just(0L));
        when(tokenRepository.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(emailService.sendEmailVerification(any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("smtp down")));

        StepVerifier.create(service.sendVerification("user@test.dev")).verifyComplete();
    }
}
