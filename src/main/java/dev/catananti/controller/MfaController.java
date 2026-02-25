package dev.catananti.controller;

import dev.catananti.dto.*;
import dev.catananti.service.AuthService;
import dev.catananti.service.EmailOtpService;
import dev.catananti.service.MfaService;
import dev.catananti.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/mfa")
@RequiredArgsConstructor
@Slf4j
public class MfaController {

    private final MfaService mfaService;
    private final EmailOtpService emailOtpService;
    private final AuthService authService;
    private final UserRepository userRepository;

    /**
     * Initiate MFA setup (TOTP or EMAIL).
     * TOTP: returns QR code data URI + secret key text.
     * EMAIL: enables email OTP immediately.
     */
    @PostMapping("/setup")
    public Mono<ResponseEntity<?>> setup(@AuthenticationPrincipal String email,
                                          @Valid @RequestBody MfaSetupRequest request) {
        log.info("MFA setup requested for user={} method={}", email, request.getMethod());
        return resolveUserId(email)
                .flatMap(userId -> {
                    if ("TOTP".equals(request.getMethod())) {
                        return mfaService.setupTotp(userId, email)
                                .map(ResponseEntity::ok);
                    } else {
                        return emailOtpService.enableEmailOtp(userId)
                                .thenReturn(ResponseEntity.ok(Map.of(
                                        "method", "EMAIL",
                                        "message", "Email OTP enabled successfully")));
                    }
                });
    }

    /**
     * Confirm TOTP setup by providing the first valid code from the authenticator app.
     */
    @PostMapping("/verify-setup")
    public Mono<ResponseEntity<Map<String, Object>>> verifySetup(@AuthenticationPrincipal String email,
                                                                   @Valid @RequestBody MfaVerifyRequest request) {
        log.info("MFA verify-setup for user={} method={}", email, request.getMethod());
        if (!"TOTP".equals(request.getMethod())) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("verified", false, "message", "Only TOTP requires setup verification")));
        }
        return resolveUserId(email)
                .flatMap(userId -> mfaService.verifySetup(userId, request.getCode()))
                .map(verified -> {
                    if (verified) {
                        return ResponseEntity.ok(Map.of("verified", true, "message", "TOTP setup complete"));
                    }
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.<String, Object>of("verified", false, "message", "Invalid code. Please try again."));
                });
    }

    /**
     * Verify MFA code during login flow (no authentication required — uses mfaToken).
     */
    @PostMapping("/verify")
    public Mono<TokenResponse> verifyLogin(@Valid @RequestBody MfaLoginVerifyRequest request) {
        log.info("MFA login verification with method={}", request.getMethod());
        return authService.completeMfaLogin(request.getMfaToken(), request.getCode(), request.getMethod());
    }

    /**
     * Send a new email OTP code (for use during login MFA challenge).
     * This endpoint is unauthenticated — requires mfaToken.
     */
    @PostMapping("/send-email-otp")
    public Mono<ResponseEntity<Map<String, String>>> sendEmailOtp(@RequestBody Map<String, String> body) {
        String mfaToken = body.get("mfaToken");
        if (mfaToken == null || mfaToken.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("message", "mfaToken is required")));
        }
        return authService.resolveMfaTokenUserId(mfaToken)
                .flatMap(userId -> emailOtpService.sendOtp(userId)
                        .thenReturn(ResponseEntity.ok(Map.of("message", "OTP sent to your email"))))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid or expired MFA token"))));
    }

    /**
     * Disable MFA completely for the authenticated user.
     */
    @DeleteMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> disable(@AuthenticationPrincipal String email) {
        log.info("MFA disable requested for user={}", email);
        return resolveUserId(email)
                .flatMap(mfaService::disableMfa);
    }

    /**
     * Get MFA status for the authenticated user.
     */
    @GetMapping("/status")
    public Mono<MfaStatusResponse> status(@AuthenticationPrincipal String email) {
        return resolveUserId(email)
                .flatMap(mfaService::getStatus);
    }

    private Mono<Long> resolveUserId(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .map(user -> user.getId());
    }
}
