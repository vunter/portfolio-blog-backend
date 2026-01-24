package dev.catananti.service;

import dev.catananti.entity.PasswordResetToken;
import dev.catananti.entity.User;
import dev.catananti.repository.PasswordResetTokenRepository;
import dev.catananti.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    @Mock
    private IdService idService;

    @InjectMocks
    private PasswordResetService passwordResetService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1234567890123456789L)
                .email("user@example.com")
                .name("Test User")
                .passwordHash("$2a$10$oldhash")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("requestPasswordReset")
    class RequestPasswordReset {

        @Test
        @DisplayName("Should send reset email when user exists and under rate limit")
        void shouldSendResetEmail_WhenUserExistsAndUnderRateLimit() {
            when(userRepository.findByEmail("user@example.com")).thenReturn(Mono.just(testUser));
            when(tokenRepository.countRecentTokensByUserId(eq(testUser.getId()), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(idService.nextId()).thenReturn(999L);
            when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendPasswordResetEmail(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(passwordResetService.requestPasswordReset("user@example.com"))
                    .verifyComplete();

            verify(emailService).sendPasswordResetEmail(eq("user@example.com"), eq("Test User"), anyString());
        }

        @Test
        @DisplayName("Should save token with correct expiry and metadata")
        void shouldSaveTokenWithCorrectExpiry() {
            when(userRepository.findByEmail("user@example.com")).thenReturn(Mono.just(testUser));
            when(tokenRepository.countRecentTokensByUserId(eq(testUser.getId()), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(idService.nextId()).thenReturn(999L);
            when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendPasswordResetEmail(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(passwordResetService.requestPasswordReset("user@example.com"))
                    .verifyComplete();

            ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
            verify(tokenRepository).save(captor.capture());

            PasswordResetToken saved = captor.getValue();
            assertThat(saved.getId()).isEqualTo(999L);
            assertThat(saved.getUserId()).isEqualTo(testUser.getId());
            assertThat(saved.getToken()).isNotNull().isNotEmpty();
            assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now());
            assertThat(saved.getUsed()).isFalse();
        }

        @Test
        @DisplayName("Should silently complete when rate limit exceeded")
        void shouldSilentlyComplete_WhenRateLimitExceeded() {
            when(userRepository.findByEmail("user@example.com")).thenReturn(Mono.just(testUser));
            when(tokenRepository.countRecentTokensByUserId(eq(testUser.getId()), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(3L));

            StepVerifier.create(passwordResetService.requestPasswordReset("user@example.com"))
                    .verifyComplete();

            verify(tokenRepository, never()).save(any());
            verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should silently complete when user not found (prevent email enumeration)")
        void shouldSilentlyComplete_WhenUserNotFound() {
            when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Mono.empty());

            StepVerifier.create(passwordResetService.requestPasswordReset("nonexistent@example.com"))
                    .verifyComplete();

            verify(tokenRepository, never()).save(any());
            verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should trim and lowercase email")
        void shouldTrimAndLowercaseEmail() {
            when(userRepository.findByEmail("user@example.com")).thenReturn(Mono.empty());

            StepVerifier.create(passwordResetService.requestPasswordReset("  User@Example.COM  "))
                    .verifyComplete();

            verify(userRepository).findByEmail("user@example.com");
        }
    }

    @Nested
    @DisplayName("validateToken")
    class ValidateToken {

        @Test
        @DisplayName("Should return true for valid unexpired token")
        void shouldReturnTrue_WhenTokenValid() {
            PasswordResetToken validToken = PasswordResetToken.builder()
                    .token("valid-token")
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .used(false)
                    .build();

            when(tokenRepository.findByTokenAndUsedFalse(anyString())).thenReturn(Mono.just(validToken));

            StepVerifier.create(passwordResetService.validateToken("valid-token"))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false for expired token")
        void shouldReturnFalse_WhenTokenExpired() {
            PasswordResetToken expiredToken = PasswordResetToken.builder()
                    .token("expired-token")
                    .expiresAt(LocalDateTime.now().minusHours(1))
                    .used(false)
                    .build();

            when(tokenRepository.findByTokenAndUsedFalse(anyString())).thenReturn(Mono.just(expiredToken));

            StepVerifier.create(passwordResetService.validateToken("expired-token"))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when token not found")
        void shouldReturnFalse_WhenTokenNotFound() {
            when(tokenRepository.findByTokenAndUsedFalse(anyString())).thenReturn(Mono.empty());

            StepVerifier.create(passwordResetService.validateToken("nonexistent"))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("resetPassword")
    class ResetPassword {

        @Test
        @DisplayName("Should reset password with valid token")
        void shouldResetPassword_WhenTokenValid() {
            PasswordResetToken validToken = PasswordResetToken.builder()
                    .id(100L)
                    .userId(testUser.getId())
                    .token("valid-token")
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .used(false)
                    .build();

            when(tokenRepository.findByTokenAndUsedFalse(anyString())).thenReturn(Mono.just(validToken));
            when(userRepository.findById(testUser.getId())).thenReturn(Mono.just(testUser));
            when(passwordEncoder.encode("StrongP@ss123!")).thenReturn("$2a$10$newhash");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(tokenRepository.markAsUsed(eq(100L), any(LocalDateTime.class))).thenReturn(Mono.empty());
            when(auditService.logPasswordReset(testUser.getId(), testUser.getEmail())).thenReturn(Mono.empty());
            when(emailService.sendPasswordChangedNotification("user@example.com", "Test User"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(passwordResetService.resetPassword("valid-token", "StrongP@ss123!"))
                    .verifyComplete();

            verify(passwordEncoder).encode("StrongP@ss123!");
            verify(userRepository).save(argThat(user -> "$2a$10$newhash".equals(user.getPasswordHash())));
            verify(tokenRepository).markAsUsed(eq(100L), any(LocalDateTime.class));
            verify(auditService).logPasswordReset(testUser.getId(), testUser.getEmail());
            verify(emailService).sendPasswordChangedNotification("user@example.com", "Test User");
        }

        @Test
        @DisplayName("Should fail with expired token")
        void shouldFail_WhenTokenExpired() {
            PasswordResetToken expiredToken = PasswordResetToken.builder()
                    .id(100L)
                    .token("expired-token")
                    .expiresAt(LocalDateTime.now().minusHours(1))
                    .used(false)
                    .build();

            when(tokenRepository.findByTokenAndUsedFalse(anyString())).thenReturn(Mono.just(expiredToken));

            StepVerifier.create(passwordResetService.resetPassword("expired-token", "StrongP@ss123!"))
                    .expectError(SecurityException.class)
                    .verify();

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should fail with nonexistent token")
        void shouldFail_WhenTokenNotFound() {
            when(tokenRepository.findByTokenAndUsedFalse(anyString())).thenReturn(Mono.empty());

            StepVerifier.create(passwordResetService.resetPassword("bad-token", "StrongP@ss123!"))
                    .expectError(SecurityException.class)
                    .verify();

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should fail with too short password")
        void shouldFail_WhenPasswordTooShort() {
            StepVerifier.create(passwordResetService.resetPassword("some-token", "Short1!"))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("password_too_short"))
                    .verify();

            verify(tokenRepository, never()).findByTokenAndUsedFalse(anyString());
        }

        @Test
        @DisplayName("Should fail with too long password")
        void shouldFail_WhenPasswordTooLong() {
            String longPassword = "A1!a" + "x".repeat(130);
            StepVerifier.create(passwordResetService.resetPassword("some-token", longPassword))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("password_too_long"))
                    .verify();
        }

        @Test
        @DisplayName("Should fail with weak password (no special char)")
        void shouldFail_WhenPasswordTooWeak() {
            StepVerifier.create(passwordResetService.resetPassword("some-token", "WeakPassword123"))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("password_too_weak"))
                    .verify();
        }

        @Test
        @DisplayName("Should fail with null password")
        void shouldFail_WhenPasswordNull() {
            StepVerifier.create(passwordResetService.resetPassword("some-token", null))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains("password_too_short"))
                    .verify();
        }

        @Test
        @DisplayName("Should fail when user not found for valid token")
        void shouldFail_WhenUserNotFoundForToken() {
            PasswordResetToken validToken = PasswordResetToken.builder()
                    .id(100L)
                    .userId(99999L)
                    .token("valid-token")
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .used(false)
                    .build();

            when(tokenRepository.findByTokenAndUsedFalse(anyString())).thenReturn(Mono.just(validToken));
            when(userRepository.findById(99999L)).thenReturn(Mono.empty());

            StepVerifier.create(passwordResetService.resetPassword("valid-token", "StrongP@ss123!"))
                    .expectErrorMatches(e -> e instanceof SecurityException
                            && e.getMessage().contains("user_not_found"))
                    .verify();
        }

        @Test
        @DisplayName("Should be resilient when password change notification email fails")
        void shouldBeResilient_WhenNotificationEmailFails() {
            PasswordResetToken validToken = PasswordResetToken.builder()
                    .id(100L)
                    .userId(testUser.getId())
                    .token("valid-token")
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .used(false)
                    .build();

            when(tokenRepository.findByTokenAndUsedFalse(anyString())).thenReturn(Mono.just(validToken));
            when(userRepository.findById(testUser.getId())).thenReturn(Mono.just(testUser));
            when(passwordEncoder.encode("StrongP@ss123!")).thenReturn("$2a$10$newhash");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(tokenRepository.markAsUsed(eq(100L), any(LocalDateTime.class))).thenReturn(Mono.empty());
            when(auditService.logPasswordReset(testUser.getId(), testUser.getEmail())).thenReturn(Mono.empty());
            when(emailService.sendPasswordChangedNotification(anyString(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("SMTP failure")));

            StepVerifier.create(passwordResetService.resetPassword("valid-token", "StrongP@ss123!"))
                    .verifyComplete();

            // Password was still reset even though notification failed
            verify(userRepository).save(argThat(user -> "$2a$10$newhash".equals(user.getPasswordHash())));
        }
    }

    // ==================== Additional Coverage: requestPasswordReset edge cases ====================

    @Nested
    @DisplayName("requestPasswordReset - edge cases")
    class RequestPasswordResetEdgeCases {

        @Test
        @DisplayName("Should silently complete when email sending fails")
        void shouldSilentlyComplete_WhenEmailSendingFails() {
            when(userRepository.findByEmail("user@example.com")).thenReturn(Mono.just(testUser));
            when(tokenRepository.countRecentTokensByUserId(eq(testUser.getId()), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(0L));
            when(idService.nextId()).thenReturn(999L);
            when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendPasswordResetEmail(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("SMTP down")));

            StepVerifier.create(passwordResetService.requestPasswordReset("user@example.com"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle rate limit at boundary (count = 2, under limit)")
        void shouldAllow_WhenUnderRateLimit() {
            when(userRepository.findByEmail("user@example.com")).thenReturn(Mono.just(testUser));
            when(tokenRepository.countRecentTokensByUserId(eq(testUser.getId()), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(2L)); // Under MAX_TOKENS_PER_HOUR of 3
            when(idService.nextId()).thenReturn(999L);
            when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(emailService.sendPasswordResetEmail(anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(passwordResetService.requestPasswordReset("user@example.com"))
                    .verifyComplete();

            verify(tokenRepository).save(any());
        }

        @Test
        @DisplayName("Should handle rate limit at exact boundary (count = 3, at limit)")
        void shouldBlock_WhenAtRateLimit() {
            when(userRepository.findByEmail("user@example.com")).thenReturn(Mono.just(testUser));
            when(tokenRepository.countRecentTokensByUserId(eq(testUser.getId()), any(LocalDateTime.class)))
                    .thenReturn(Mono.just(3L)); // Exactly at MAX_TOKENS_PER_HOUR of 3

            StepVerifier.create(passwordResetService.requestPasswordReset("user@example.com"))
                    .verifyComplete();

            verify(tokenRepository, never()).save(any());
        }
    }
}
