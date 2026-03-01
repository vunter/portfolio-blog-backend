package dev.catananti.service;

import dev.catananti.entity.EmailChangeToken;
import dev.catananti.entity.User;
import dev.catananti.repository.EmailChangeTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailChangeServiceTest {

    @Mock
    private EmailChangeTokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private IdService idService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private EmailChangeService service;

    private User testUser;
    private Long testUserId;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "siteUrl", "http://localhost:4200");

        testUserId = 1234567890123456789L;
        testUser = User.builder()
                .id(testUserId)
                .email("old@example.com")
                .name("Test User")
                .passwordHash("hashedPassword")
                .role("ADMIN")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ==================== initiateEmailChange ====================

    @Test
    @DisplayName("Should initiate email change successfully")
    void shouldInitiateEmailChangeSuccessfully() {
        when(tokenRepository.countRecentTokensByUserId(eq(testUserId), any(LocalDateTime.class)))
                .thenReturn(Mono.just(0L));
        when(idService.nextId()).thenReturn(100L);
        when(tokenRepository.save(any(EmailChangeToken.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(emailService.sendEmailChangeVerification(eq("new@example.com"), eq("Test User"), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.initiateEmailChange(testUserId, "new@example.com", "Test User"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject when rate limit exceeded")
    void shouldRejectWhenRateLimitExceeded() {
        when(tokenRepository.countRecentTokensByUserId(eq(testUserId), any(LocalDateTime.class)))
                .thenReturn(Mono.just(3L));

        StepVerifier.create(service.initiateEmailChange(testUserId, "new@example.com", "Test User"))
                .expectErrorMatches(e -> e instanceof ResponseStatusException
                        && ((ResponseStatusException) e).getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                .verify();
    }

    @Test
    @DisplayName("Should succeed even if email send fails")
    void shouldSucceedEvenIfEmailSendFails() {
        when(tokenRepository.countRecentTokensByUserId(eq(testUserId), any(LocalDateTime.class)))
                .thenReturn(Mono.just(0L));
        when(idService.nextId()).thenReturn(100L);
        when(tokenRepository.save(any(EmailChangeToken.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(emailService.sendEmailChangeVerification(eq("new@example.com"), eq("Test User"), anyString()))
                .thenReturn(Mono.error(new RuntimeException("SMTP down")));

        StepVerifier.create(service.initiateEmailChange(testUserId, "new@example.com", "Test User"))
                .verifyComplete();
    }

    // ==================== verifyEmailChange ====================

    @Test
    @DisplayName("Should verify email change successfully")
    void shouldVerifyEmailChangeSuccessfully() {
        String plainToken = "test-plain-token";
        String hashedToken = DigestUtils.sha256Hex(plainToken);

        EmailChangeToken token = EmailChangeToken.builder()
                .id(200L)
                .userId(testUserId)
                .newEmail("new@example.com")
                .token(hashedToken)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .used(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(tokenRepository.findByTokenAndUsedFalse(hashedToken)).thenReturn(Mono.just(token));
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(Mono.just(false));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(tokenRepository.markAsUsed(eq(200L), any(LocalDateTime.class))).thenReturn(Mono.empty());
        when(idService.nextId()).thenReturn(300L);
        when(tokenRepository.save(any(EmailChangeToken.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditService.logEmailChange(eq(testUserId), eq("old@example.com"), eq("new@example.com")))
                .thenReturn(Mono.empty());
        when(emailService.sendEmailChangedNotification(eq("old@example.com"), eq("Test User"), eq("new@example.com"), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.verifyEmailChange(plainToken))
                .assertNext(result -> assertThat(result).isEqualTo("new@example.com"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject invalid token on verify")
    void shouldRejectInvalidToken() {
        String plainToken = "invalid-token";
        String hashedToken = DigestUtils.sha256Hex(plainToken);

        when(tokenRepository.findByTokenAndUsedFalse(hashedToken)).thenReturn(Mono.empty());

        StepVerifier.create(service.verifyEmailChange(plainToken))
                .expectErrorMatches(e -> e instanceof ResponseStatusException
                        && ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("Should reject expired token on verify")
    void shouldRejectExpiredToken() {
        String plainToken = "expired-token";
        String hashedToken = DigestUtils.sha256Hex(plainToken);

        EmailChangeToken token = EmailChangeToken.builder()
                .id(200L)
                .userId(testUserId)
                .newEmail("new@example.com")
                .token(hashedToken)
                .expiresAt(LocalDateTime.now().minusHours(1))
                .used(false)
                .createdAt(LocalDateTime.now().minusHours(2))
                .build();

        when(tokenRepository.findByTokenAndUsedFalse(hashedToken)).thenReturn(Mono.just(token));

        StepVerifier.create(service.verifyEmailChange(plainToken))
                .expectErrorMatches(e -> e instanceof ResponseStatusException
                        && ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("Should reject if new email already taken")
    void shouldRejectIfNewEmailAlreadyTaken() {
        String plainToken = "taken-email-token";
        String hashedToken = DigestUtils.sha256Hex(plainToken);

        EmailChangeToken token = EmailChangeToken.builder()
                .id(200L)
                .userId(testUserId)
                .newEmail("taken@example.com")
                .token(hashedToken)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .used(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(tokenRepository.findByTokenAndUsedFalse(hashedToken)).thenReturn(Mono.just(token));
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(Mono.just(true));

        StepVerifier.create(service.verifyEmailChange(plainToken))
                .expectErrorMatches(e -> e instanceof ResponseStatusException
                        && ((ResponseStatusException) e).getStatusCode() == HttpStatus.CONFLICT)
                .verify();
    }

    // ==================== revertEmailChange ====================

    @Test
    @DisplayName("Should revert email change successfully")
    void shouldRevertEmailChangeSuccessfully() {
        String plainToken = "revert-token";
        String hashedToken = DigestUtils.sha256Hex(plainToken);

        EmailChangeToken token = EmailChangeToken.builder()
                .id(400L)
                .userId(testUserId)
                .newEmail("old@example.com")
                .oldEmail("new@example.com")
                .token(hashedToken)
                .expiresAt(LocalDateTime.now().plusHours(48))
                .used(false)
                .createdAt(LocalDateTime.now())
                .build();

        // User currently has the new email (after the change was applied)
        testUser.setEmail("new@example.com");

        when(tokenRepository.findByTokenAndUsedFalse(hashedToken)).thenReturn(Mono.just(token));
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(tokenRepository.markAsUsed(eq(400L), any(LocalDateTime.class))).thenReturn(Mono.empty());
        when(auditService.logEmailRevert(eq(testUserId), eq("new@example.com"), eq("old@example.com")))
                .thenReturn(Mono.empty());
        when(emailService.sendEmailRevertedNotification(eq("new@example.com"), eq("Test User"), eq("old@example.com")))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.revertEmailChange(plainToken))
                .assertNext(result -> assertThat(result).isEqualTo("old@example.com"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject revert with null oldEmail")
    void shouldRejectRevertWithNullOldEmail() {
        String plainToken = "non-revert-token";
        String hashedToken = DigestUtils.sha256Hex(plainToken);

        // This is a regular verification token, not a revert token (no oldEmail)
        EmailChangeToken token = EmailChangeToken.builder()
                .id(500L)
                .userId(testUserId)
                .newEmail("new@example.com")
                .oldEmail(null)
                .token(hashedToken)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .used(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(tokenRepository.findByTokenAndUsedFalse(hashedToken)).thenReturn(Mono.just(token));

        StepVerifier.create(service.revertEmailChange(plainToken))
                .expectErrorMatches(e -> e instanceof ResponseStatusException
                        && ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("Should reject expired revert token")
    void shouldRejectExpiredRevertToken() {
        String plainToken = "expired-revert-token";
        String hashedToken = DigestUtils.sha256Hex(plainToken);

        EmailChangeToken token = EmailChangeToken.builder()
                .id(600L)
                .userId(testUserId)
                .newEmail("old@example.com")
                .oldEmail("new@example.com")
                .token(hashedToken)
                .expiresAt(LocalDateTime.now().minusHours(1))
                .used(false)
                .createdAt(LocalDateTime.now().minusHours(49))
                .build();

        when(tokenRepository.findByTokenAndUsedFalse(hashedToken)).thenReturn(Mono.just(token));

        StepVerifier.create(service.revertEmailChange(plainToken))
                .expectErrorMatches(e -> e instanceof ResponseStatusException
                        && ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("Should reject invalid revert token")
    void shouldRejectInvalidRevertToken() {
        String plainToken = "nonexistent-revert-token";
        String hashedToken = DigestUtils.sha256Hex(plainToken);

        when(tokenRepository.findByTokenAndUsedFalse(hashedToken)).thenReturn(Mono.empty());

        StepVerifier.create(service.revertEmailChange(plainToken))
                .expectErrorMatches(e -> e instanceof ResponseStatusException
                        && ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }
}
