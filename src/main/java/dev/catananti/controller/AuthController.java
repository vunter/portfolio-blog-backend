package dev.catananti.controller;

import dev.catananti.dto.AuthResponse;
import dev.catananti.dto.LoginRequest;
import dev.catananti.dto.RegisterRequest;
import dev.catananti.dto.TokenResponse;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.service.AuthService;
import dev.catananti.service.RecaptchaService;
import dev.catananti.service.RefreshTokenService;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.IpAddressExtractor;
import dev.catananti.util.PiiMasker;
import dev.catananti.service.EmailChangeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final RecaptchaService recaptchaService;
    private final EmailChangeService emailChangeService;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;
    private final ServerCsrfTokenRepository csrfTokenRepository;
    private final BlogMetrics blogMetrics;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationMs;

    @Value("${jwt.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${jwt.cookie.domain:}")
    private String cookieDomain;

    private static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    // F-076: Rate-limited via nginx login zone (5r/m)
    // F-077: Password complexity enforced via @Pattern annotation on RegisterRequest DTO
    // Q7.14: Unified login endpoint (merged v1 and v2 - returns TokenResponse with refresh token)
    @PostMapping("/login")
    public Mono<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                        ServerHttpRequest httpRequest,
                                        ServerHttpResponse httpResponse,
                                        ServerWebExchange exchange) {
        log.info("Login attempt for user='{}' from ip={}", PiiMasker.maskEmail(request.getEmail()), IpAddressExtractor.extractClientIp(httpRequest));
        String clientIp = IpAddressExtractor.extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeaders().getFirst("User-Agent");
        return recaptchaService.verify(request.getRecaptchaToken(), "login")
                .then(authService.loginWithRefreshToken(request, clientIp, userAgent))
                .flatMap(response -> {
                    addAccessTokenCookie(httpResponse, response.getAccessToken());
                    addRefreshTokenCookie(httpResponse, response.getRefreshToken(), Boolean.TRUE.equals(request.getRememberMe()));
                    // Q12.3: Track login success
                    blogMetrics.incrementLoginSuccess();
                    // Q4.2: Rotate CSRF token after auth state change to prevent token fixation
                    return rotateCsrfToken(exchange).thenReturn(response);
                })
                .doOnError(e -> blogMetrics.incrementLoginFailure());
    }

    // F-076: Rate-limited via nginx login zone (5r/m)
    @PostMapping("/register")
    public Mono<ResponseEntity<TokenResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            ServerHttpRequest httpRequest,
            ServerHttpResponse httpResponse,
            ServerWebExchange exchange) {
        log.info("Registration attempt for user='{}' from ip={}", PiiMasker.maskEmail(request.email()), IpAddressExtractor.extractClientIp(httpRequest));
        String clientIp = IpAddressExtractor.extractClientIp(httpRequest);
        return recaptchaService.verify(request.recaptchaToken(), "register")
                .then(authService.register(request, clientIp))
                .flatMap(tokenResponse -> {
                    addAccessTokenCookie(httpResponse, tokenResponse.getAccessToken());
                    addRefreshTokenCookie(httpResponse, tokenResponse.getRefreshToken(), false);
                    // Q4.2: Rotate CSRF token after auth state change
                    return rotateCsrfToken(exchange)
                            .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(tokenResponse));
                });
    }

    @PostMapping("/refresh")
    public Mono<TokenResponse> refreshToken(ServerHttpRequest httpRequest,
                                             ServerHttpResponse httpResponse) {
        String clientIp = IpAddressExtractor.extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeaders().getFirst("User-Agent");

        // BUG-04: Read ALL refresh_token cookies — browsers may send duplicates if
        // SameSite attributes differed between OAuth (Lax) and Auth (previously Strict).
        // Try each cookie until one succeeds, starting from the last (most recent).
        java.util.List<HttpCookie> refreshCookies = httpRequest.getCookies()
                .getOrDefault(REFRESH_TOKEN_COOKIE, java.util.Collections.emptyList());

        log.info("Token refresh requested from ip={}, refresh_token cookies={}", clientIp, refreshCookies.size());

        if (refreshCookies.isEmpty()) {
            return Mono.error(new IllegalArgumentException("error.refresh_token_required"));
        }

        // Try cookies in reverse order (last = most recently set = most likely valid)
        java.util.List<String> tokenValues = refreshCookies.stream()
                .map(HttpCookie::getValue)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toList());
        java.util.Collections.reverse(tokenValues);

        if (tokenValues.isEmpty()) {
            return Mono.error(new IllegalArgumentException("error.refresh_token_required"));
        }

        // Try each token; on failure, fall through to the next
        Mono<TokenResponse> result = Mono.empty();
        for (String token : tokenValues) {
            final String currentToken = token;
            result = result.switchIfEmpty(
                authService.refreshAccessToken(currentToken, clientIp, userAgent)
                    .doOnNext(response -> {
                        addAccessTokenCookie(httpResponse, response.getAccessToken());
                        addRefreshTokenCookie(httpResponse, response.getRefreshToken(), true);
                    })
                    .onErrorResume(e -> {
                        if (tokenValues.size() > 1) {
                            log.warn("Refresh failed for one of {} cookies, trying next: {}",
                                    tokenValues.size(), e.getMessage());
                        }
                        return Mono.empty();
                    })
            );
        }

        return result.switchIfEmpty(Mono.defer(() -> {
            // All cookies failed — re-throw with the first token to get proper error handling
            return authService.refreshAccessToken(tokenValues.get(0), clientIp, userAgent)
                    .doOnNext(response -> {
                        addAccessTokenCookie(httpResponse, response.getAccessToken());
                        addRefreshTokenCookie(httpResponse, response.getRefreshToken(), true);
                    });
        }));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(ServerHttpRequest httpRequest,
                              ServerHttpResponse httpResponse,
                              ServerWebExchange exchange) {
        log.info("Logout requested");
        // Extract tokens from cookies
        String refreshToken = extractRefreshTokenFromCookie(httpRequest);
        String accessToken = extractAccessTokenFromCookie(httpRequest);

        // First blacklist tokens, THEN clear cookies, THEN rotate CSRF token
        Mono<Void> logoutMono = Mono.empty();
        if (refreshToken != null) {
            logoutMono = authService.logout(refreshToken, accessToken);
        } else if (accessToken != null) {
            logoutMono = authService.logout(null, accessToken);
        }
        return logoutMono
                .then(Mono.fromRunnable(() -> clearAuthCookies(httpResponse)))
                // Q4.2: Rotate CSRF token after auth state change
                .then(rotateCsrfToken(exchange))
                .onErrorResume(e -> {
                    log.warn("Logout blacklisting failed, clearing cookies anyway: {}", e.getMessage());
                    clearAuthCookies(httpResponse);
                    return rotateCsrfToken(exchange);
                });
    }

    // F-075: Explicitly require authentication (also enforced by SecurityConfig /api/v1/admin/auth/** rule)
    @GetMapping("/verify")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public Mono<Map<String, Object>> verifyToken(@AuthenticationPrincipal org.springframework.security.core.userdetails.UserDetails userDetails) {
        log.debug("Token verification requested");
        // FEAT-08: Return user info confirming token is valid
        if (userDetails == null) {
            return Mono.just(Map.of("valid", false));
        }
        return Mono.just(Map.of(
            "valid", true,
            "username", userDetails.getUsername(),
            "roles", userDetails.getAuthorities().stream()
                .map(a -> a.getAuthority()).toList()
        ));
    }

    // ===== CSRF Token Rotation =====

    /**
     * Q4.2: Rotate the CSRF token after auth state changes (login/register/logout)
     * to prevent CSRF token fixation attacks where an attacker pre-sets a known token
     * before the user authenticates.
     */
    private Mono<Void> rotateCsrfToken(ServerWebExchange exchange) {
        return csrfTokenRepository.generateToken(exchange)
                .flatMap(token -> csrfTokenRepository.saveToken(exchange, token));
    }

    // ===== Cookie Helpers =====

    // BUG-04: All auth cookies MUST use SameSite=Lax to match OAuth2Controller.
    // Using Strict here while OAuth uses Lax can cause browsers to maintain duplicate
    // cookies (one Lax from OAuth, one Strict from refresh), and getFirst() reads the
    // stale one — causing "revoked token reuse" failures after every rotation.
    // Lax is safe: it prevents cross-site POST/fetch from sending cookies (CSRF protection)
    // while allowing same-site AJAX and top-level navigations.
    private static final String COOKIE_SAME_SITE = "Lax";

    private void addAccessTokenCookie(ServerHttpResponse response, String token) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(ACCESS_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api")
                .maxAge(Duration.ofMillis(jwtExpirationMs))
                .sameSite(COOKIE_SAME_SITE);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        response.addCookie(builder.build());
    }

    private void addRefreshTokenCookie(ServerHttpResponse response, String token, boolean rememberMe) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(REFRESH_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api/v1/admin/auth")
                // BUG-03: rememberMe=true: persistent cookie (7 days); false: 24-hour persistent cookie
                // Using Duration.ofHours(24) instead of session cookie to ensure the refresh token
                // survives page reloads and is consistently sent across user agents
                .maxAge(rememberMe ? Duration.ofDays(7) : Duration.ofHours(24))
                .sameSite(COOKIE_SAME_SITE);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        response.addCookie(builder.build());
    }

    private void clearAuthCookies(ServerHttpResponse response) {
        // BUG-04: Clear cookies with BOTH Lax and Strict SameSite to ensure any stale
        // Strict cookies from before this fix are also cleared from the browser
        for (String sameSite : new String[]{COOKIE_SAME_SITE, "Strict"}) {
            ResponseCookie.ResponseCookieBuilder accessBuilder = ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                    .httpOnly(true)
                    .secure(cookieSecure)
                    .path("/api")
                    .maxAge(0)
                    .sameSite(sameSite);
            ResponseCookie.ResponseCookieBuilder refreshBuilder = ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
                    .httpOnly(true)
                    .secure(cookieSecure)
                    .path("/api/v1/admin/auth")
                    .maxAge(0)
                    .sameSite(sameSite);
            if (cookieDomain != null && !cookieDomain.isBlank()) {
                accessBuilder.domain(cookieDomain);
                refreshBuilder.domain(cookieDomain);
            }
            response.addCookie(accessBuilder.build());
            response.addCookie(refreshBuilder.build());
        }
    }

    private String extractRefreshTokenFromCookie(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(REFRESH_TOKEN_COOKIE);
        return cookie != null ? cookie.getValue() : null;
    }

    private String extractAccessTokenFromCookie(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(ACCESS_TOKEN_COOKIE);
        return cookie != null ? cookie.getValue() : null;
    }

    @GetMapping("/verify-email-change")
    public Mono<ResponseEntity<Map<String, String>>> verifyEmailChange(@RequestParam String token) {
        return emailChangeService.verifyEmailChange(token)
                .map(newEmail -> ResponseEntity.ok(Map.of(
                        "message", "Email changed successfully",
                        "email", newEmail)))
                .onErrorResume(e -> {
                    log.warn("Email change verification failed: {}", e.getMessage());
                    return Mono.just(ResponseEntity.badRequest().body(Map.of(
                            "message", "Invalid or expired verification link")));
                });
    }

    @GetMapping("/revert-email-change")
    public Mono<ResponseEntity<Map<String, String>>> revertEmailChange(@RequestParam String token) {
        return emailChangeService.revertEmailChange(token)
                .map(restoredEmail -> ResponseEntity.ok(Map.of(
                        "message", "Email reverted successfully",
                        "email", restoredEmail)))
                .onErrorResume(e -> {
                    log.warn("Email revert failed: {}", e.getMessage());
                    return Mono.just(ResponseEntity.badRequest().body(Map.of(
                            "message", "Invalid or expired revert link")));
                });
    }

    @GetMapping("/sessions")
    public Mono<ResponseEntity<java.util.List<Map<String, Object>>>> getActiveSessions(
            @AuthenticationPrincipal String email) {
        return userRepository.findByEmail(email)
                .flatMapMany(user -> refreshTokenService.getActiveSessions(user.getId()))
                .map(session -> {
                    Map<String, Object> dto = new java.util.LinkedHashMap<>();
                    dto.put("id", session.getId());
                    dto.put("deviceName", session.getDeviceName());
                    dto.put("ipAddress", IpAddressExtractor.anonymizeIp(session.getIpAddress()));
                    dto.put("createdAt", session.getCreatedAt());
                    dto.put("lastUsedAt", session.getLastUsedAt());
                    dto.put("expiresAt", session.getExpiresAt());
                    return dto;
                })
                .collectList()
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Mono<ResponseEntity<Void>> revokeSession(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal String email) {
        return userRepository.findByEmail(email)
                .flatMap(user -> refreshTokenService.revokeTokenById(sessionId, user.getId()))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @DeleteMapping("/sessions")
    public Mono<ResponseEntity<Void>> revokeAllOtherSessions(
            @AuthenticationPrincipal String email,
            ServerHttpRequest httpRequest) {
        String currentRefreshToken = extractRefreshTokenFromCookie(httpRequest);
        if (currentRefreshToken == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return userRepository.findByEmail(email)
                .flatMap(user -> refreshTokenService.revokeAllExceptCurrent(user.getId(), currentRefreshToken))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
