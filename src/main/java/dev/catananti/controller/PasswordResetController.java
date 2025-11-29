package dev.catananti.controller;

import dev.catananti.dto.PasswordResetConfirmRequest;
import dev.catananti.dto.PasswordResetRequest;
import dev.catananti.service.PasswordResetService;
import dev.catananti.service.RecaptchaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;

/**
 * Controller for password reset functionality.
 * Endpoints are public but rate-limited.
 * TODO F-087: Add constant-time comparison for token validation to prevent timing-based token enumeration
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
@Tag(name = "Password Reset", description = "Password reset endpoints")
@Slf4j
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    private final RecaptchaService recaptchaService;
    private final MessageSource messageSource;

    private String msg(Locale locale, String key) {
        return messageSource.getMessage(key, null, key, locale);
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.OK)
    @Operation(
            summary = "Request password reset",
            description = "Sends a password reset email if the email exists. Always returns success to prevent email enumeration."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request processed (email sent if account exists)"),
            @ApiResponse(responseCode = "400", description = "Invalid email format"),
            @ApiResponse(responseCode = "429", description = "Too many requests")
    })
    public Mono<Map<String, String>> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        log.info("Password reset requested");
        return recaptchaService.verify(request.getRecaptchaToken(), "forgot_password")
                .then(passwordResetService.requestPasswordReset(request.getEmail()))
                .then(Mono.deferContextual(ctx -> {
                    Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                    return Mono.just(Map.of("message", msg(locale, "success.password_reset_requested")));
                }));
    }

    @GetMapping("/reset-password/validate")
    @Operation(
            summary = "Validate reset token",
            description = "Check if a password reset token is valid before showing the reset form. Always returns a consistent response to prevent token enumeration."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token validation result"),
            @ApiResponse(responseCode = "400", description = "Missing token")
    })
    public Mono<Map<String, Object>> validateResetToken(@RequestParam String token) {
        log.debug("Validating reset token");
        if (token == null || token.isBlank()) {
            return Mono.deferContextual(ctx -> {
                Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                return Mono.just(Map.of(
                        "valid", (Object) false,
                        "message", msg(locale, "error.token_required")
                ));
            });
        }
        return passwordResetService.validateToken(token)
                .flatMap(valid -> Mono.deferContextual(ctx -> {
                    Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                    return Mono.just(Map.of(
                            "valid", (Object) valid,
                            "message", valid ? msg(locale, "success.token_valid") : msg(locale, "success.token_invalid")
                    ));
                }));
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    @Operation(
            summary = "Reset password",
            description = "Reset password using a valid token. Token can only be used once."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password successfully reset"),
            @ApiResponse(responseCode = "400", description = "Invalid request or password doesn't meet requirements"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired token")
    })
    public Mono<Map<String, String>> resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        log.info("Password reset executed");
        return passwordResetService.resetPassword(request.getToken(), request.getNewPassword())
                .then(Mono.deferContextual(ctx -> {
                    Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                    return Mono.just(Map.of("message", msg(locale, "success.password_reset_complete")));
                }));
    }
}
