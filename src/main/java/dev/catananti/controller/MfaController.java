package dev.catananti.controller;

import dev.catananti.dto.*;
import dev.catananti.service.AuthService;
import dev.catananti.service.EmailOtpService;
import dev.catananti.service.MfaService;
import dev.catananti.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/mfa")
@RequiredArgsConstructor
@Slf4j
public class MfaController {

    private final MfaService mfaService;
    private final EmailOtpService emailOtpService;
    private final AuthService authService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ReactiveStringRedisTemplate redisTemplate;

    private static final String OTP_SENDS_PREFIX = "mfa:otp-sends:";
    private static final int MAX_OTP_SENDS = 3;

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
        String sendsKey = OTP_SENDS_PREFIX + mfaToken;
        return redisTemplate.opsForValue().increment(sendsKey)
                .flatMap(sends -> {
                    if (sends == 1) {
                        return redisTemplate.expire(sendsKey, Duration.ofMinutes(5)).thenReturn(sends);
                    }
                    return Mono.just(sends);
                })
                .flatMap(sends -> {
                    if (sends > MAX_OTP_SENDS) {
                        return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                .body(Map.of("message", "Too many OTP requests")));
                    }
                    return authService.resolveMfaTokenUserId(mfaToken)
                            .flatMap(userId -> emailOtpService.sendOtp(userId)
                                    .thenReturn(ResponseEntity.ok(Map.of("message", "OTP sent to your email"))))
                            .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                    .body(Map.of("message", "Invalid or expired MFA token"))));
                });
    }

    /**
     * Disable MFA completely for the authenticated user.
     */
    @DeleteMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> disable(@AuthenticationPrincipal String email, @RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (password == null || password.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password required"));
        }
        log.info("MFA disable requested for user={}", email);
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(user -> {
                    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid password"));
                    }
                    return mfaService.disableMfa(user.getId());
                });
    }

    /**
     * Get MFA status for the authenticated user.
     */
    @GetMapping("/status")
    public Mono<MfaStatusResponse> status(@AuthenticationPrincipal String email) {
        return resolveUserId(email)
                .flatMap(mfaService::getStatus);
    }

    /**
     * Generate new backup codes (replaces existing ones).
     */
    @PostMapping("/backup-codes")
    public Mono<ResponseEntity<Map<String, Object>>> generateBackupCodes(@AuthenticationPrincipal String email) {
        log.info("Backup codes generation requested for user={}", email);
        return resolveUserId(email)
                .flatMap(mfaService::generateBackupCodes)
                .map(codes -> ResponseEntity.ok(Map.<String, Object>of("codes", codes)));
    }

    /**
     * Get remaining backup codes count.
     */
    @GetMapping("/backup-codes/count")
    public Mono<Map<String, Long>> backupCodesCount(@AuthenticationPrincipal String email) {
        return resolveUserId(email)
                .flatMap(mfaService::getRemainingBackupCodeCount)
                .map(count -> Map.of("remaining", count));
    }

    private Mono<Long> resolveUserId(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")))
                .map(user -> user.getId());
    }
}
