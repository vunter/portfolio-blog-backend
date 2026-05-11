package dev.catananti.controller;

import dev.catananti.dto.*;
import dev.catananti.service.AuthService;
import dev.catananti.service.EmailOtpService;
import dev.catananti.service.MfaService;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.IpAddressExtractor;
import dev.catananti.util.PiiMasker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
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
    private final MessageSource messageSource;

    private String msg(Locale locale, String key) {
        return messageSource.getMessage(key, null, key, locale);
    }

    private String msg(Locale locale, String key, Object... args) {
        return messageSource.getMessage(key, args, key, locale);
    }

    private static String hashMfaToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static final String OTP_SENDS_PREFIX = "mfa:otp-sends:";
    private static final String MFA_RATE_PREFIX = "mfa:rate:";

    @Value("${mfa.email-otp.max-sends:3}")
    private int maxOtpSends;

    @Value("${mfa.rate-limit.max-ops-per-window:5}")
    private int maxMfaOpsPerWindow;

    @Value("${mfa.rate-limit.window-minutes:5}")
    private int mfaRateWindowMinutes;

    /**
     * Rate-limit MFA operations per user (5 per 5 minutes).
     */
    private Mono<Boolean> checkMfaRateLimit(String email) {
        String key = MFA_RATE_PREFIX + email;
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        return redisTemplate.expire(key, Duration.ofMinutes(mfaRateWindowMinutes)).thenReturn(count);
                    }
                    return Mono.just(count);
                })
                .map(count -> count <= maxMfaOpsPerWindow);
    }

    private <T> Mono<ResponseEntity<T>> rateLimitExceeded() {
        return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build());
    }

    /**
     * Initiate MFA setup (TOTP or EMAIL).
     * TOTP: returns QR code data URI + secret key text.
     * EMAIL: sends a verification code to the user's email.
     */
    @PostMapping("/setup")
    public Mono<ResponseEntity<?>> setup(@AuthenticationPrincipal String email,
                                          @Valid @RequestBody MfaSetupRequest request) {
        return checkMfaRateLimit(email).flatMap(allowed -> {
            if (!allowed) return rateLimitExceeded();
            log.info("MFA setup requested for user={} method={}", PiiMasker.maskEmail(email), request.getMethod());
            return resolveUserId(email)
                    .flatMap(userId -> {
                        if ("TOTP".equals(request.getMethod())) {
                            return mfaService.setupTotp(userId, email)
                                    .map(ResponseEntity::ok);
                        } else {
                            return emailOtpService.initSetup(userId)
                                    .then(Mono.deferContextual(ctx -> {
                                        Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                                        return Mono.just(ResponseEntity.ok(Map.<String, Object>of(
                                                "method", "EMAIL",
                                                "message", msg(locale, "mfa.otp.sent"))));
                                    }));
                        }
                    });
        });
    }

    /**
     * Confirm MFA setup by providing the verification code.
     * TOTP: code from authenticator app. EMAIL: code from email.
     */
    @PostMapping("/verify-setup")
    public Mono<ResponseEntity<Map<String, Object>>> verifySetup(@AuthenticationPrincipal String email,
                                                                   @Valid @RequestBody MfaVerifyRequest request) {
        return checkMfaRateLimit(email).flatMap(allowed -> {
            if (!allowed) return rateLimitExceeded();
            log.info("MFA verify-setup for user={} method={}", PiiMasker.maskEmail(email), request.getMethod());
            return resolveUserId(email)
                    .flatMap(userId -> {
                        if ("EMAIL".equals(request.getMethod())) {
                            return emailOtpService.verifySetup(userId, request.getCode())
                                    .then(mfaService.generateBackupCodes(userId))
                                    .flatMap(codes -> Mono.deferContextual(ctx -> {
                                        Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                                        return Mono.just(ResponseEntity.ok(Map.<String, Object>of(
                                                "verified", true,
                                                "message", msg(locale, "mfa.otp.setup.complete"),
                                                "backupCodes", codes)));
                                    }));
                        }
                        return mfaService.verifySetup(userId, request.getCode())
                                .flatMap(codes -> Mono.deferContextual(ctx -> {
                                    Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                                    if (codes.isEmpty()) {
                                        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                                .body(Map.<String, Object>of("verified", false, "message", msg(locale, "mfa.invalid.code"))));
                                    }
                                    return Mono.just(ResponseEntity.ok(Map.<String, Object>of(
                                            "verified", true,
                                            "message", msg(locale, "mfa.totp.setup.complete"),
                                            "backupCodes", codes)));
                                }));
                    })
                    .onErrorResume(IllegalArgumentException.class, e ->
                            Mono.just(ResponseEntity.badRequest()
                                    .body(Map.of("verified", false, "message", e.getMessage()))));
        });
    }

    /**
     * Verify MFA code during login flow (no authentication required — uses mfaToken).
     */
    @PostMapping("/verify")
    public Mono<TokenResponse> verifyLogin(@Valid @RequestBody MfaLoginVerifyRequest request,
                                            ServerHttpRequest httpRequest) {
        log.info("MFA login verification with method={}", request.getMethod());
        String clientIp = IpAddressExtractor.extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeaders().getFirst("User-Agent");
        return authService.completeMfaLogin(request.getMfaToken(), request.getCode(), request.getMethod(),
                clientIp, userAgent);
    }

    /**
     * Send a new email OTP code (for use during login MFA challenge).
     * This endpoint is unauthenticated — requires mfaToken.
     */
    @PostMapping("/send-email-otp")
    public Mono<ResponseEntity<Map<String, String>>> sendEmailOtp(@RequestBody Map<String, String> body) {
        String mfaToken = body.get("mfaToken");
        if (mfaToken == null || mfaToken.isBlank()) {
            return Mono.deferContextual(ctx -> {
                Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                return Mono.just(ResponseEntity.badRequest().body(Map.of("message", msg(locale, "mfa.token.required"))));
            });
        }
        // SEC: hash the MFA token before using it as a Redis key, mirroring AuthService.
        // Storing the raw token would let a Redis read leak in-flight, usable tokens.
        String sendsKey = OTP_SENDS_PREFIX + hashMfaToken(mfaToken);
        return redisTemplate.opsForValue().increment(sendsKey)
                .flatMap(sends -> {
                    if (sends == 1) {
                        return redisTemplate.expire(sendsKey, Duration.ofMinutes(5)).thenReturn(sends);
                    }
                    return Mono.just(sends);
                })
                .flatMap(sends -> {
                    if (sends > maxOtpSends) {
                        return Mono.deferContextual(ctx -> {
                            Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                    .body(Map.of("message", msg(locale, "mfa.too.many.requests"))));
                        });
                    }
                    return authService.resolveMfaTokenUserId(mfaToken)
                            .flatMap(userId -> emailOtpService.sendOtp(userId)
                                    .then(Mono.deferContextual(ctx -> {
                                        Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                                        return Mono.just(ResponseEntity.ok(Map.of("message", msg(locale, "mfa.otp.sent"))));
                                    })))
                            .onErrorResume(e -> Mono.deferContextual(ctx -> {
                                Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                        .body(Map.of("message", msg(locale, "mfa.token.invalid"))));
                            }));
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
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "error.password_required"));
        }
        log.info("MFA disable requested for user={}", PiiMasker.maskEmail(email));
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(user ->
                    Mono.fromCallable(() -> passwordEncoder.matches(password, user.getPasswordHash()))
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .flatMap(matches -> {
                            if (!matches) {
                                return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "error.password_invalid"));
                            }
                            return mfaService.disableMfa(user.getId());
                        })
                );
    }

    /**
     * Disable a single MFA method after OTP verification.
     */
    @PostMapping("/disable-method")
    public Mono<ResponseEntity<Map<String, Object>>> disableMethod(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {
        return checkMfaRateLimit(email).flatMap(allowed -> {
            if (!allowed) return rateLimitExceeded();
            String method = body.get("method");
            String code = body.get("code");
            if (method == null || method.isBlank() || code == null || code.isBlank()) {
                return Mono.deferContextual(ctx -> {
                    Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                    return Mono.just(ResponseEntity.badRequest()
                            .body(Map.<String, Object>of("success", false, "message", msg(locale, "mfa.method.code.required"))));
                });
            }
            log.info("MFA disable-method={} requested for user={}", method, PiiMasker.maskEmail(email));
            return resolveUserId(email)
                    .flatMap(userId -> mfaService.verifyAnyCode(userId, code)
                            .flatMap(valid -> Mono.deferContextual(ctx -> {
                                Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                                if (!valid) {
                                    return Mono.just(ResponseEntity.badRequest()
                                            .body(Map.<String, Object>of("success", false, "message", msg(locale, "mfa.invalid.verification.code"))));
                                }
                                return mfaService.disableMethod(userId, method)
                                        .then(Mono.deferContextual(ctx2 -> {
                                            Locale locale2 = ctx2.getOrDefault("locale", Locale.ENGLISH);
                                            return Mono.just(ResponseEntity.ok(Map.<String, Object>of("success", true,
                                                    "message", msg(locale2, "mfa.method.disabled", method))));
                                        }));
                            })));
        });
    }

    /**
     * Send an email OTP to the authenticated user (for security verification).
     */
    @PostMapping("/send-otp")
    public Mono<ResponseEntity<Map<String, String>>> sendAuthenticatedOtp(@AuthenticationPrincipal String email) {
        return checkMfaRateLimit(email).flatMap(allowed -> {
            if (!allowed) return rateLimitExceeded();
            log.info("Authenticated OTP send requested for user={}", PiiMasker.maskEmail(email));
            return resolveUserId(email)
                    .flatMap(userId -> emailOtpService.sendOtp(userId)
                            .then(Mono.deferContextual(ctx -> {
                                Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                                return Mono.just(ResponseEntity.ok(Map.of("message", msg(locale, "mfa.otp.sent"))));
                            })))
                    .onErrorResume(e -> {
                        log.error("Failed to send OTP for user={}", PiiMasker.maskEmail(email), e);
                        return Mono.deferContextual(ctx -> {
                            Locale locale = ctx.getOrDefault("locale", Locale.ENGLISH);
                            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                    .body(Map.of("message", msg(locale, "mfa.otp.send.failed"))));
                        });
                    });
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
        return checkMfaRateLimit(email).flatMap(allowed -> {
            if (!allowed) return rateLimitExceeded();
            log.info("Backup codes generation requested for user={}", PiiMasker.maskEmail(email));
            return resolveUserId(email)
                    .flatMap(mfaService::generateBackupCodes)
                    .map(codes -> ResponseEntity.ok(Map.<String, Object>of("codes", codes)));
        });
    }

    private Mono<Long> resolveUserId(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "error.user_not_found")))
                .map(user -> user.getId());
    }
}
