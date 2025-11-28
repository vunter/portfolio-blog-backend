package dev.catananti.service;

import dev.catananti.dto.AuthResponse;
import dev.catananti.dto.LoginRequest;
import dev.catananti.dto.RegisterRequest;
import dev.catananti.dto.TokenResponse;
import dev.catananti.entity.User;
import dev.catananti.exception.AccountLockedException;
import dev.catananti.repository.UserRepository;
import dev.catananti.security.JwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
// TODO F-155: Use constant-time comparison in login path to mitigate timing attacks on user existence
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
                       @Autowired(required = false) LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.messageSource = messageSource;
        this.idService = idService;
        this.htmlSanitizerService = htmlSanitizerService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.emailService = emailService;
        this.loginAttemptService = Optional.ofNullable(loginAttemptService);
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

    public Mono<AuthResponse> login(LoginRequest request, String clientIp) {
        String loginKey = request.getEmail().toLowerCase();

        // Check if account is locked
        return isBlocked(loginKey)
                .flatMap(blocked -> {
                    if (blocked) {
                        return getRemainingLockoutTime(loginKey)
                                .flatMap(remaining -> Mono.error(new AccountLockedException(remaining / 60 + 1)));
                    }
                    return performLogin(request, loginKey, clientIp);
                });
    }

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
                                    return recordFailedAttempt(loginKey, clientIp)
                                            .flatMap(_ -> getRemainingAttempts(loginKey)
                                                    .flatMap(remaining -> {
                                                        String msgKey = remaining > 0
                                                                ? "error.invalid_credentials_remaining"
                                                                : "error.account_locked_attempts";
                                                        Object[] args = remaining > 0 ? new Object[]{remaining} : null;
                                                        String message = messageSource.getMessage(msgKey, args, Locale.ENGLISH);
                                                        return Mono.<User>error(new BadCredentialsException(message));
                                                    }));
                                }
                                return clearFailedAttempts(loginKey).thenReturn(user);
                            }))
                .switchIfEmpty(Mono.defer(() ->
                        recordFailedAttempt(loginKey, clientIp)
                                .then(Mono.error(new BadCredentialsException("error.invalid_credentials")))
                ));
    }

    private Mono<AuthResponse> performLogin(LoginRequest request, String loginKey, String clientIp) {
        return verifyCredentials(loginKey, request.getPassword(), clientIp)
                .map(user -> {
                    String token = tokenProvider.generateToken(user.getEmail(), user.getRole());
                    log.debug("User logged in: {} from IP: {}", user.getEmail(), clientIp);
                    return AuthResponse.builder()
                            .token(token)
                            .type("Bearer")
                            .expiresIn(jwtExpirationMs / 1000)
                            .email(user.getEmail())
                            .name(user.getName())
                            .role(user.getRole())
                            .build();
                });
    }

    public Mono<TokenResponse> loginWithRefreshToken(LoginRequest request, String clientIp) {
        String loginKey = request.getEmail().toLowerCase();

        return isBlocked(loginKey)
                .flatMap(blocked -> {
                    if (blocked) {
                        return getRemainingLockoutTime(loginKey)
                                .flatMap(remaining -> Mono.error(new AccountLockedException(remaining / 60 + 1)));
                    }
                    return performLoginWithRefreshToken(request, loginKey, clientIp);
                });
    }

    private Mono<TokenResponse> performLoginWithRefreshToken(LoginRequest request, String loginKey, String clientIp) {
        return verifyCredentials(loginKey, request.getPassword(), clientIp)
                .flatMap(user -> {
                    String accessToken = tokenProvider.generateToken(user.getEmail(), user.getRole());
                    return refreshTokenService.createRefreshToken(user.getId())
                            .map(refreshToken -> {
                                log.debug("User logged in with refresh token: {} from IP: {}", user.getEmail(), clientIp);
                                return TokenResponse.builder()
                                        .accessToken(accessToken)
                                        .refreshToken(refreshToken.getToken())
                                        .tokenType("Bearer")
                                        .expiresIn(jwtExpirationMs / 1000)
                                        .email(user.getEmail())
                                        .name(user.getName())
                                        .build();
                            });
                });
    }

    public Mono<TokenResponse> refreshAccessToken(String refreshToken) {
        return refreshTokenService.verifyAndRotate(refreshToken)
                .flatMap(newRefreshToken -> userRepository.findById(newRefreshToken.getUserId())
                        .switchIfEmpty(Mono.error(new SecurityException("error.user_not_found")))
                        .map(user -> {
                            String accessToken = tokenProvider.generateToken(user.getEmail(), user.getRole());
                            
                            log.debug("Access token refreshed for user: {}", user.getEmail());
                            
                            return TokenResponse.builder()
                                    .accessToken(accessToken)
                                    .refreshToken(newRefreshToken.getToken())
                                    .tokenType("Bearer")
                                    .expiresIn(jwtExpirationMs / 1000)
                                    .email(user.getEmail())
                                    .name(user.getName())
                                    .build();
                        }));
    }

    public Mono<TokenResponse> register(RegisterRequest request, String clientIp) {
        String email = request.email().toLowerCase().trim();

        return userRepository.existsByEmail(email)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.<User>error(new ResponseStatusException(HttpStatus.CONFLICT, "error.email_already_registered"));
                    }

                    User user = User.builder()
                            .id(idService.nextId())
                            .name(htmlSanitizerService.stripHtml(request.name()))
                            .email(email)
                            // F-156: Offload blocking BCrypt.encode to boundedElastic
                            .passwordHash(null) // set below reactively
                            .role("VIEWER")
                            .active(true)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return Mono.fromCallable(() -> passwordEncoder.encode(request.password()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(encodedPassword -> {
                                user.setPasswordHash(encodedPassword);
                                return userRepository.save(user);
                            });
                })
                .flatMap(user -> {
                    String accessToken = tokenProvider.generateToken(user.getEmail(), user.getRole());

                    return emailService.sendRegistrationWelcome(user.getEmail(), user.getName())
                            .onErrorResume(e -> {
                                log.warn("Failed to send welcome email to {}: {}", user.getEmail(), e.getMessage());
                                return Mono.empty();
                            })
                            .then(refreshTokenService.createRefreshToken(user.getId()))
                            .map(refreshToken -> {
                                log.info("New user registered: {} from IP: {}", user.getEmail(), clientIp);

                                return TokenResponse.builder()
                                        .accessToken(accessToken)
                                        .refreshToken(refreshToken.getToken())
                                        .tokenType("Bearer")
                                        .expiresIn(jwtExpirationMs / 1000)
                                        .email(user.getEmail())
                                        .name(user.getName())
                                        .build();
                            });
                });
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

    public String getEmailFromToken(String token) {
        return tokenProvider.getEmailFromToken(token);
    }
}
