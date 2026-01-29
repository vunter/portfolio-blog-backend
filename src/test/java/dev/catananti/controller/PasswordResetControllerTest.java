package dev.catananti.controller;

import dev.catananti.dto.PasswordResetConfirmRequest;
import dev.catananti.dto.PasswordResetRequest;
import dev.catananti.service.PasswordResetService;
import dev.catananti.service.RecaptchaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetControllerTest {

    @Mock
    private PasswordResetService passwordResetService;

    @Mock
    private RecaptchaService recaptchaService;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private PasswordResetController controller;

    @Nested
    @DisplayName("POST /api/v1/admin/auth/forgot-password")
    class RequestPasswordReset {

        @Test
        @DisplayName("Should request password reset successfully")
        void shouldRequestPasswordResetSuccessfully() {
            PasswordResetRequest request = PasswordResetRequest.builder()
                    .email("user@example.com")
                    .recaptchaToken("valid-token")
                    .build();

            when(recaptchaService.verify("valid-token", "forgot_password")).thenReturn(Mono.empty());
            when(passwordResetService.requestPasswordReset("user@example.com")).thenReturn(Mono.empty());
            when(messageSource.getMessage(eq("success.password_reset_requested"), any(), eq("success.password_reset_requested"), any(Locale.class)))
                    .thenReturn("Password reset email sent");

            StepVerifier.create(controller.requestPasswordReset(request)
                            .contextWrite(Context.of("locale", Locale.ENGLISH)))
                    .assertNext(result -> {
                        assertThat(result).containsKey("message");
                        assertThat(result.get("message")).isEqualTo("Password reset email sent");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should propagate error when recaptcha fails")
        void shouldPropagateErrorWhenRecaptchaFails() {
            PasswordResetRequest request = PasswordResetRequest.builder()
                    .email("user@example.com")
                    .recaptchaToken("bad-token")
                    .build();

            when(recaptchaService.verify("bad-token", "forgot_password"))
                    .thenReturn(Mono.error(new RuntimeException("Recaptcha failed")));
            // .then() eagerly evaluates its argument as a Java expression
            when(passwordResetService.requestPasswordReset("user@example.com")).thenReturn(Mono.empty());

            StepVerifier.create(controller.requestPasswordReset(request)
                            .contextWrite(Context.of("locale", Locale.ENGLISH)))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/auth/reset-password/validate")
    class ValidateResetToken {

        @Test
        @DisplayName("Should return valid=true for valid token")
        void shouldReturnValidForValidToken() {
            when(passwordResetService.validateToken("valid-token")).thenReturn(Mono.just(true));
            when(messageSource.getMessage(eq("success.token_valid"), any(), eq("success.token_valid"), any(Locale.class)))
                    .thenReturn("Token is valid");

            StepVerifier.create(controller.validateResetToken("valid-token")
                            .contextWrite(Context.of("locale", Locale.ENGLISH)))
                    .assertNext(result -> {
                        assertThat(result.get("valid")).isEqualTo(true);
                        assertThat(result.get("message")).isEqualTo("Token is valid");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return valid=false for invalid token")
        void shouldReturnInvalidForInvalidToken() {
            when(passwordResetService.validateToken("expired-token")).thenReturn(Mono.just(false));
            when(messageSource.getMessage(eq("success.token_invalid"), any(), eq("success.token_invalid"), any(Locale.class)))
                    .thenReturn("Token is invalid or expired");

            StepVerifier.create(controller.validateResetToken("expired-token")
                            .contextWrite(Context.of("locale", Locale.ENGLISH)))
                    .assertNext(result -> {
                        assertThat(result.get("valid")).isEqualTo(false);
                        assertThat(result.get("message")).isEqualTo("Token is invalid or expired");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return error for blank token")
        void shouldReturnErrorForBlankToken() {
            when(messageSource.getMessage(eq("error.token_required"), any(), eq("error.token_required"), any(Locale.class)))
                    .thenReturn("Token is required");

            StepVerifier.create(controller.validateResetToken("")
                            .contextWrite(Context.of("locale", Locale.ENGLISH)))
                    .assertNext(result -> {
                        assertThat(result.get("valid")).isEqualTo(false);
                        assertThat(result.get("message")).isEqualTo("Token is required");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/auth/reset-password")
    class ResetPassword {

        @Test
        @DisplayName("Should reset password successfully")
        void shouldResetPasswordSuccessfully() {
            PasswordResetConfirmRequest request = PasswordResetConfirmRequest.builder()
                    .token("valid-token")
                    .newPassword("NewP@ssw0rd123!")
                    .build();

            when(passwordResetService.resetPassword("valid-token", "NewP@ssw0rd123!")).thenReturn(Mono.empty());
            when(messageSource.getMessage(eq("success.password_reset_complete"), any(), eq("success.password_reset_complete"), any(Locale.class)))
                    .thenReturn("Password successfully reset");

            StepVerifier.create(controller.resetPassword(request)
                            .contextWrite(Context.of("locale", Locale.ENGLISH)))
                    .assertNext(result -> {
                        assertThat(result).containsKey("message");
                        assertThat(result.get("message")).isEqualTo("Password successfully reset");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should propagate error for invalid token during reset")
        void shouldPropagateErrorForInvalidToken() {
            PasswordResetConfirmRequest request = PasswordResetConfirmRequest.builder()
                    .token("expired-token")
                    .newPassword("NewP@ssw0rd123!")
                    .build();

            when(passwordResetService.resetPassword("expired-token", "NewP@ssw0rd123!"))
                    .thenReturn(Mono.error(new RuntimeException("Invalid or expired token")));

            StepVerifier.create(controller.resetPassword(request)
                            .contextWrite(Context.of("locale", Locale.ENGLISH)))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }
}
