package dev.catananti.service;

import dev.catananti.dto.LoginRequest;
import dev.catananti.dto.RegisterRequest;
import dev.catananti.dto.TokenResponse;
import dev.catananti.entity.RefreshToken;
import dev.catananti.entity.User;
import dev.catananti.exception.AccountDeactivatedException;
import dev.catananti.exception.AccountLockedException;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.repository.UserRepository;
import dev.catananti.security.JwtTokenProvider;
import org.springframework.transaction.annotation.Transactional;
import dev.catananti.util.PiiMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final Optional<LoginAttemptService> loginAttemptService;
    private final MessageSource messageSource;
    private final IdService idService;
    private final HtmlSanitizerService htmlSanitizerService;
    private final TokenBlacklistService tokenBlacklistService;
    private final EmailService emailService;
    private final EmailVerificationService emailVerificationService;
    private final AuditService auditService;
    private final Optional<MfaService> mfaService;
    private final Optional<EmailOtpService> emailOtpService;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final org.springframework.transaction.reactive.TransactionalOperator transactionalOperator;

    private static final String MFA_TOKEN_PREFIX = "mfa:token:";
    private static final String MFA_ATTEMPTS_PREFIX = "mfa:attempts:";

    @Value("${mfa.token-ttl-minutes:5}")
    private int mfaTokenTtlMinutes;

    @Value("${mfa.max-attempts:5}")
    private int maxMfaAttempts;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationMs;

    @Autowired
    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenService refreshTokenService,
                       MessageSource messageSource,
                       IdService idService,
                       HtmlSanitizerService htmlSanitizerService,
                       TokenBlacklistService tokenBlacklistService,
                       EmailService emailService,
                       EmailVerificationService emailVerificationService,
                       AuditService auditService,
                       ReactiveStringRedisTemplate redisTemplate,
                       org.springframework.transaction.reactive.TransactionalOperator transactionalOperator,
                       @Autowired(required = false) LoginAttemptService loginAttemptService,
                       @Autowired(required = false) MfaService mfaService,
                       @Autowired(required = false) EmailOtpService emailOtpService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.messageSource = messageSource;
        this.idService = idService;
        this.htmlSanitizerService = htmlSanitizerService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.emailService = emailService;
        this.emailVerificationService = emailVerificationService;
        this.auditService = auditService;
        this.redisTemplate = redisTemplate;
        this.transactionalOperator = transactionalOperator;
        this.loginAttemptService = Optional.ofNullable(loginAttemptService);
        this.mfaService = Optional.ofNullable(mfaService);
        this.emailOtpService = Optional.ofNullable(emailOtpService);
        if (loginAttemptService == null) {
            log.warn("SEC: LoginAttemptService not available — brute force protection is DISABLED");
        }
    }

    // Helper methods for optional LoginAttemptService
    private Mono<Boolean> isBlocked(String key) {
        return loginAttemptService.map(svc -> svc.isBlocked(key)).orElse(Mono.just(false));
    }
    private Mono<Long> getRemainingLockoutTime(String key) {
        return loginAttemptService.map(svc -> svc.getRemainingLockoutTime(key)).orElse(Mono.just(0L));
    }
    private Mono<Integer> recordFailedAttempt(String key, String clientIp) {
        return loginAttemptService.map(svc -> svc.recordFailedAttempt(key, clientIp)).orElse(Mono.just(0));
    }
    private Mono<Integer> getRemainingAttempts(String key) {
        return loginAttemptService.map(svc -> svc.getRemainingAttempts(key)).orElse(Mono.just(999));
    }
    private Mono<Void> clearFailedAttempts(String key) {
        return loginAttemptService.map(svc -> svc.clearFailedAttempts(key)).orElse(Mono.empty());
    }

    // AUD18-M7: the old login()/performLogin() pair was removed. No controller called it
    // (Q7.14 unified the endpoints on loginWithRefreshToken), and it minted a full JWT
    // WITHOUT the MFA challenge — a latent MFA bypass if it were ever wired back up.
    // The AuthResponse DTO it returned was deleted with it.

    /**
     * Verify user credentials: password check, failed-attempt tracking, and lockout.
     * Extracted to eliminate duplication between login flows.
     */
    private Mono<User> verifyCredentials(String loginKey, String password, String clientIp) {
        return userRepository.findByEmail(loginKey)
                .flatMap(user ->
                    // F-156: Offload blocking BCrypt to boundedElastic to avoid blocking reactor thread
                    Mono.fromCallable(() -> passwordEncoder.matches(password, user.getPasswordHash()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(matches -> {
                                if (!matches) {
                                    // AUD19C-DEACT: the localized remaining-attempts message that used
                                    // to be built here was dead weight — GlobalExceptionHandler
                                    // deliberately masks BadCredentialsException as a generic
                                    // "invalid credentials" (SEC: never expose attempt counts to the
                                    // client), so the resolved text never reached anyone. Keep the
                                    // observability in a server-side log and throw the plain key.
                                    return recordFailedAttempt(loginKey, clientIp)
                                            .flatMap(_ -> getRemainingAttempts(loginKey)
                                                    .flatMap(remaining -> {
                                                        log.warn("Failed login for {}: {} attempts remaining",
                                                                PiiMasker.maskEmail(loginKey), remaining);
                                                        return Mono.<User>error(new BadCredentialsException(
                                                                remaining > 0
                                                                        ? "error.invalid_credentials"
                                                                        : "error.account_locked_attempts"));
                                                    }));
                                }
                                // SEC: Reject deactivated users at credential-verification time so a fresh
                                // token is never minted for them. The per-request filter only catches
                                // already-issued tokens; without this check, a deactivated user can simply
                                // log in again to bypass the cache eviction.
                                if (!Boolean.TRUE.equals(user.getActive())) {
                                    // AUD19C-DEACT: dedicated exception -> 403 error.account_deactivated.
                                    // BadCredentialsException here got masked as "invalid credentials",
                                    // gaslighting the holder about a correct password; the MFA and
                                    // refresh paths already reveal deactivation.
                                    log.warn("Login denied — account is deactivated: {}", PiiMasker.maskEmail(user.getEmail()));
                                    return Mono.<User>error(new AccountDeactivatedException());
                                }
                                return clearFailedAttempts(loginKey).thenReturn(user);
                            }))
                .switchIfEmpty(Mono.defer(() ->
                        // F-155: Perform a dummy BCrypt hash when user is not found to mitigate
                        // timing-based user enumeration (equalizes response time with valid users)
                        Mono.fromCallable(() -> passwordEncoder.matches("dummy", "$2a$12$000000000000000000000uGzFnJwmxBwDL5m49XxfGkCgM0PjWVe"))
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(recordFailedAttempt(loginKey, clientIp))
                                .then(Mono.error(new BadCredentialsException("error.invalid_credentials")))
                ));
    }

    public Mono<TokenResponse> loginWithRefreshToken(LoginRequest request, String clientIp, String userAgent) {
        String loginKey = request.getEmail().toLowerCase();

        return isBlocked(loginKey)
                .flatMap(blocked -> {
                    if (blocked) {
                        return getRemainingLockoutTime(loginKey)
                                .flatMap(remaining -> Mono.error(new AccountLockedException(remaining / 60 + 1)));
                    }
                    return performLoginWithRefreshToken(request, loginKey, clientIp, userAgent);
                });
    }

    private Mono<TokenResponse> performLoginWithRefreshToken(LoginRequest request, String loginKey, String clientIp, String userAgent) {
        return verifyCredentials(loginKey, request.getPassword(), clientIp)
                .flatMap(user -> {
                    if (Boolean.TRUE.equals(user.getMfaEnabled())) {
                        return issueMfaChallenge(user);
                    }
                    return issueFullTokens(user, clientIp, userAgent);
                });
    }

    private Mono<TokenResponse> issueFullTokens(User user, String clientIp, String userAgent) {
        String accessToken = tokenProvider.generateToken(user.getId(), user.getRole());
        return refreshTokenService.createRefreshToken(user.getId(), clientIp, userAgent)
                .map(refreshToken -> {
                    log.debug("User logged in with refresh token: {} from IP: {}", PiiMasker.maskEmail(user.getEmail()), clientIp);
                    return TokenResponse.builder()
                            .accessToken(accessToken)
                            .refreshToken(refreshToken.getToken())
                            .tokenType("Bearer")
                            .expiresIn(jwtExpirationMs / 1000)
                            .email(user.getEmail())
                            .name(user.getName())
                            .build();
                })
                // AUD19-LOGIN: this is the single funnel where a real session is minted for
                // BOTH password logins (performLoginWithRefreshToken, non-MFA branch) and MFA
                // completions (completeMfaLogin after OTP/TOTP/backup verification) — the
                // OAuth2 MFA challenge also completes here via /admin/mfa/verify. Logging
                // here (and NOT at issueMfaChallenge) records exactly one LOGIN row per
                // successful login. Fire-and-forget by design: the detached .subscribe() is
                // the intentional exception to the "no .subscribe() inside operators" rule
                // (see register()) because the audit row is best-effort and must never fail
                // or delay token delivery; logAction already swallows persistence errors,
                // the error consumer below only guards future regressions.
                .doOnNext(response -> auditService.logLoginSuccess(user.getId(), user.getEmail(), clientIp)
                        .subscribe(null, e -> log.warn("AUD19-LOGIN: failed to record LOGIN audit for {}: {}",
                                PiiMasker.maskEmail(user.getEmail()), e.getMessage())));
    }

    /**
     * Issue an MFA challenge: return a temporary mfaToken (stored in Redis) instead of real JWT.
     * The client must call /api/v1/admin/mfa/verify with this token + OTP code.
     * SEC: The MFA token is hashed before use as a Redis key so a Redis compromise
     * does not reveal tokens that could be used to complete MFA login.
     *
     * <p>AUD18-A10: public so {@link OAuth2Service} can issue the SAME challenge —
     * OAuth2 logins previously minted full tokens without ever consulting
     * {@code user.getMfaEnabled()}, silently bypassing MFA for social logins.
     */
    public Mono<TokenResponse> issueMfaChallenge(User user) {
        String mfaToken = UUID.randomUUID().toString();
        String redisKey = MFA_TOKEN_PREFIX + hashMfaToken(mfaToken);
        // Store userId in Redis with short TTL; plain UUID returned to client
        return redisTemplate.opsForValue()
                .set(redisKey, String.valueOf(user.getId()), Duration.ofMinutes(mfaTokenTtlMinutes))
                .then(sendMfaCodeIfEmail(user))
                .thenReturn(TokenResponse.builder()
                        .mfaRequired(true)
                        .mfaToken(mfaToken)
                        // AUD19C-MFAMETHOD: tell the FE which form to land on (TOTP vs EMAIL)
                        .mfaMethod(user.getMfaPreferredMethod())
                        .email(user.getEmail())
                        .name(user.getName())
                        .build());
    }

    /**
     * If the user's preferred MFA method is EMAIL, automatically send the OTP.
     */
    private Mono<Void> sendMfaCodeIfEmail(User user) {
        if ("EMAIL".equals(user.getMfaPreferredMethod()) && emailOtpService.isPresent()) {
            return emailOtpService.get().sendOtp(user.getId());
        }
        return Mono.empty();
    }

    /**
     * Complete MFA login: verify the OTP code, then issue real JWT tokens.
     */
    public Mono<TokenResponse> completeMfaLogin(String mfaToken, String code, String method,
                                                  String clientIp, String userAgent) {
        String hashedToken = hashMfaToken(mfaToken);
        String redisKey = MFA_TOKEN_PREFIX + hashedToken;
        String attemptsKey = MFA_ATTEMPTS_PREFIX + hashedToken;

        // Check token existence BEFORE incrementing the brute-force counter
        return redisTemplate.opsForValue().get(redisKey)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "error.mfa_token_invalid")))
                .flatMap(userIdStr -> redisTemplate.opsForValue().increment(attemptsKey)
                        .flatMap(attempts -> {
                            if (attempts == 1) {
                                return redisTemplate.expire(attemptsKey, Duration.ofMinutes(mfaTokenTtlMinutes)).thenReturn(attempts);
                            }
                            return Mono.just(attempts);
                        })
                        .flatMap(attempts -> {
                            if (attempts > maxMfaAttempts) {
                                // AUD19C-MFA429: i18n key instead of hardcoded English — the reason is
                                // resolved (or passed through) by GlobalExceptionHandler.msg() exactly
                                // like the sibling error.mfa_token_invalid / error.mfa_code_invalid keys.
                                return redisTemplate.delete(redisKey)
                                        .then(redisTemplate.delete(attemptsKey))
                                        .then(Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                                "error.mfa_too_many_attempts")));
                            }
                            Long userId = Long.valueOf(userIdStr);
                            Mono<Boolean> verifyMono = switch (method) {
                                case "TOTP" -> mfaService.map(svc -> svc.verifyTotp(userId, code))
                                        .orElse(Mono.just(false));
                                case "EMAIL" -> emailOtpService.map(svc -> svc.verifyOtp(userId, code))
                                        .orElse(Mono.just(false));
                                case "BACKUP" -> mfaService.map(svc -> svc.verifyBackupCode(userId, code))
                                        .orElse(Mono.just(false));
                                default -> Mono.just(false);
                            };
                            return verifyMono.flatMap(valid -> {
                                if (!valid) {
                                    return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "error.mfa_code_invalid"));
                                }
                                // Delete mfaToken and attempts counter from Redis (one-time use)
                                return redisTemplate.delete(redisKey)
                                        .then(redisTemplate.delete(attemptsKey))
                                        .then(userRepository.findById(userId))
                                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "error.user_not_found")))
                                        .flatMap(user -> {
                                            // AUD18-L9: re-check active before minting — the account may
                                            // have been deactivated between the password step and the
                                            // OTP step, and this path must not resurrect it.
                                            if (!Boolean.TRUE.equals(user.getActive())) {
                                                log.warn("MFA login denied — account is deactivated: {}",
                                                        PiiMasker.maskEmail(user.getEmail()));
                                                return Mono.error(new ResponseStatusException(
                                                        HttpStatus.UNAUTHORIZED, "error.account_deactivated"));
                                            }
                                            return issueFullTokens(user, clientIp, userAgent);
                                        });
                            });
                        }));
    }

    /**
     * Resolve an authenticated principal's email to the persisted user id.
     * Provided so controllers (e.g. {@code AuthController}) don't have to
     * depend on {@link UserRepository} directly.
     *
     * @param email principal email (typically from {@code @AuthenticationPrincipal})
     * @return {@code Mono} emitting the user id, or empty when no user matches
     */
    public Mono<Long> resolveUserIdByEmail(String email) {
        return userRepository.findByEmail(email).map(User::getId);
    }

    /**
     * Resolve an mfaToken to the associated userId (for sending email OTP during login).
     */
    public Mono<Long> resolveMfaTokenUserId(String mfaToken) {
        String redisKey = MFA_TOKEN_PREFIX + hashMfaToken(mfaToken);
        return redisTemplate.opsForValue().get(redisKey)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "error.mfa_token_invalid")))
                .map(Long::valueOf);
    }

    public Mono<TokenResponse> refreshAccessToken(String refreshToken, String clientIp, String userAgent) {
        return refreshTokenService.verifyAndRotate(refreshToken, clientIp, userAgent)
                .flatMap(newRefreshToken -> userRepository.findById(newRefreshToken.getUserId())
                        .switchIfEmpty(Mono.error(new SecurityException("error.user_not_found")))
                        .flatMap(user -> {
                            // AUD18-L9: a deactivated account must not keep its session alive
                            // through rotation. The rotated token is revoked along with every
                            // other token for the user before the request is rejected.
                            if (!Boolean.TRUE.equals(user.getActive())) {
                                log.warn("Refresh denied — account is deactivated: {}",
                                        PiiMasker.maskEmail(user.getEmail()));
                                return refreshTokenService.revokeAllUserTokens(user.getId())
                                        .then(Mono.error(new SecurityException("error.account_deactivated")));
                            }
                            String accessToken = tokenProvider.generateToken(user.getId(), user.getRole());

                            log.debug("Access token refreshed for user: {}", PiiMasker.maskEmail(user.getEmail()));

                            return Mono.just(TokenResponse.builder()
                                    .accessToken(accessToken)
                                    .refreshToken(newRefreshToken.getToken())
                                    .tokenType("Bearer")
                                    .expiresIn(jwtExpirationMs / 1000)
                                    .email(user.getEmail())
                                    .name(user.getName())
                                    .build());
                        }));
    }

    public Mono<TokenResponse> register(RegisterRequest request, String clientIp) {
        String email = request.email().toLowerCase().trim();

        // Step 1: BCrypt hash off the reactor thread BEFORE the transactional boundary.
        // Running BCrypt unconditionally also equalizes wall-clock time between
        // "account exists" and "new account" paths to mitigate timing-based enumeration.
        Mono<String> encodedPasswordMono = Mono.fromCallable(() -> passwordEncoder.encode(request.password()))
                .subscribeOn(Schedulers.boundedElastic())
                .cache(Duration.ofSeconds(10));

        return Mono.zip(userRepository.existsByEmail(email), encodedPasswordMono)
                .flatMap(tuple -> {
                    boolean exists = tuple.getT1();
                    String encodedPassword = tuple.getT2();

                    if (exists) {
                        // Best-effort "someone tried to register your email" notification.
                        // Always returns 409 to the caller so the FE can render a clear
                        // conflict state rather than a 200 with null tokens.
                        //
                        // SEG-6 (accepted tradeoff — do NOT change to a neutral 202): the 409 +
                        // email reveals that an account exists (account enumeration). This is an
                        // INTENTIONAL, documented UX decision the register flow + tests depend on.
                        // The enumeration risk is bounded because POST /register is rate-limited
                        // (nginx login zone, 5r/m — see AuthController F-076) and gated by reCAPTCHA,
                        // and the BCrypt hash above runs unconditionally so timing does not leak
                        // existence either.
                        return emailService.sendTextEmail(email,
                                        "Account registration attempt",
                                        "An account with this email already exists. If this was you, try logging in.")
                                .onErrorResume(e -> {
                                    log.warn("Failed to send existing-account email: {}", e.getMessage());
                                    return Mono.empty();
                                })
                                .then(Mono.error(new DuplicateResourceException("error.email_already_registered")));
                    }

                    // Step 2: Run only the DB writes inside the transactional boundary so
                    // a refresh-token failure rolls the user insert back. Side-effects
                    // (welcome email, logging) happen AFTER commit and are part of the
                    // reactive chain — never .subscribe() inside an operator (creates
                    // untracked subscriptions and silently swallows errors).
                    // TX-01: self-invocation bypasses the @Transactional proxy, so the
                    // atomicity persistNewUser documents must come from the operator here.
                    return transactionalOperator.transactional(persistNewUser(email, encodedPassword, request.name()))
                            .flatMap(saved -> {
                                User savedUser = saved.getT1();
                                RefreshToken refreshToken = saved.getT2();
                                String accessToken = tokenProvider.generateToken(savedUser.getId(), savedUser.getRole());
                                log.info("New user registered: {} from IP: {}", PiiMasker.maskEmail(savedUser.getEmail()), clientIp);
                                TokenResponse response = TokenResponse.builder()
                                        .accessToken(accessToken)
                                        .refreshToken(refreshToken.getToken())
                                        .tokenType("Bearer")
                                        .expiresIn(jwtExpirationMs / 1000)
                                        .email(savedUser.getEmail())
                                        .name(savedUser.getName())
                                        .build();
                                // Welcome email — best-effort, never rolls back the registration.
                                return emailService.sendRegistrationWelcome(savedUser.getEmail(), savedUser.getName())
                                        .onErrorResume(e -> {
                                            log.warn("Failed to send welcome email to {}: {}", PiiMasker.maskEmail(savedUser.getEmail()), e.getMessage());
                                            return Mono.empty();
                                        })
                                        .thenReturn(response);
                            })
                            // Address-ownership verification — AFTER the transactional
                            // boundary (SMTP inside a transaction would hold a pool
                            // connection through the whole handshake) and best-effort.
                            .flatMap(response -> emailVerificationService.sendVerification(email)
                                    .onErrorResume(e -> {
                                        log.warn("Verification email failed at registration: {}", e.getMessage());
                                        return Mono.empty();
                                    })
                                    .thenReturn(response));
                });
    }

    /**
     * Persists the new user row AND its first refresh token. Atomicity comes from the
     * TransactionalOperator wrap at the call site in register() — a @Transactional
     * annotation here would be silently ignored (self-invocation never crosses the
     * Spring proxy), which is exactly the bug this replaced (TX-01).
     */
    private Mono<reactor.util.function.Tuple2<User, RefreshToken>> persistNewUser(String email, String encodedPassword, String rawName) {
        User user = User.builder()
                .id(idService.nextId())
                .name(htmlSanitizerService.stripHtml(rawName))
                .email(email)
                .passwordHash(encodedPassword)
                .role("VIEWER")
                .active(true)
                .termsAccepted(true)
                .termsAcceptedAt(LocalDateTime.now())
                .termsVersion("1.0")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return userRepository.save(user)
                .flatMap(saved -> refreshTokenService.createRefreshToken(saved.getId())
                        .map(token -> reactor.util.function.Tuples.of(saved, token)));
    }

    public Mono<Void> logout(String refreshToken, String accessToken) {
        // Blacklist the access token so it cannot be reused (JWT parsing is blocking)
        Mono<Void> blacklistMono = accessToken != null
                ? Mono.fromCallable(() -> {
                    String jti = tokenProvider.getJtiFromToken(accessToken);
                    long remainingMs = tokenProvider.getRemainingLifetimeMs(accessToken);
                    return jti != null && remainingMs > 0 ? Map.entry(jti, remainingMs) : null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(entry -> entry != null
                        ? tokenBlacklistService.blacklist(entry.getKey(), entry.getValue()).then()
                        : Mono.empty())
                .onErrorResume(e -> {
                    log.warn("Failed to blacklist access token on logout: {}", e.getMessage());
                    return Mono.empty();
                })
                : Mono.empty();

        // Revoke the refresh token in the database
        Mono<Void> revokeMono = refreshToken != null
                ? refreshTokenService.revokeToken(refreshToken)
                : Mono.empty();

        return Mono.when(blacklistMono, revokeMono)
                .doOnSuccess(v -> log.info("User logged out"));
    }

    public boolean validateToken(String token) {
        return tokenProvider.validateToken(token);
    }

    /**
     * SEC: Hash an MFA token with SHA-256 before using as a Redis key.
     * Ensures that a Redis compromise does not expose usable MFA tokens.
     */
    private String hashMfaToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
