package dev.catananti.controller;

import dev.catananti.dto.LoginRequest;
import dev.catananti.dto.RegisterRequest;
import dev.catananti.dto.TokenResponse;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.security.AuthCookieService;
import dev.catananti.service.AuthService;
import dev.catananti.service.RecaptchaService;
import dev.catananti.service.RefreshTokenService;
import dev.catananti.util.IpAddressExtractor;
import dev.catananti.util.PiiMasker;
import dev.catananti.service.EmailChangeService;
import dev.catananti.service.EmailVerificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final RecaptchaService recaptchaService;
    private final EmailChangeService emailChangeService;
    private final EmailVerificationService emailVerificationService;
    private final RefreshTokenService refreshTokenService;
    private final ServerCsrfTokenRepository csrfTokenRepository;
    private final BlogMetrics blogMetrics;
    // AUD19C-C1B: cookie contract extracted to AuthCookieService so the MFA login
    // completion (MfaController) and the OAuth2 callback emit identical cookies.
    private final AuthCookieService authCookieService;

    private static final String ACCESS_TOKEN_COOKIE = AuthCookieService.ACCESS_TOKEN_COOKIE;
    private static final String REFRESH_TOKEN_COOKIE = AuthCookieService.REFRESH_TOKEN_COOKIE;

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
                    // AUD19C-C1C: an MFA challenge is NOT a session. The response carries only
                    // the short-lived mfaToken — setting auth cookies here would clobber any
                    // existing session with blanks, and counting it as a login success would
                    // double-count every MFA login (the real success is recorded when
                    // /admin/mfa/verify completes). CSRF is still rotated: the auth state
                    // transitioned (anonymous -> challenged) and fixation protection applies.
                    if (Boolean.TRUE.equals(response.getMfaRequired())) {
                        return rotateCsrfToken(exchange).thenReturn(response);
                    }
                    authCookieService.addAccessTokenCookie(httpResponse, response.getAccessToken());
                    authCookieService.addRefreshTokenCookie(httpResponse, response.getRefreshToken(), Boolean.TRUE.equals(request.getRememberMe()));
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
                    authCookieService.addAccessTokenCookie(httpResponse, tokenResponse.getAccessToken());
                    authCookieService.addRefreshTokenCookie(httpResponse, tokenResponse.getRefreshToken(), false);
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

        // Try each token; on failure, fall through to the next.
        // AUD18-L4: remember the FIRST failure so the all-failed case can rethrow it —
        // the previous code re-invoked refreshAccessToken just to reproduce the error,
        // which ran verifyAndRotate (and its theft detection) a second time per token.
        java.util.concurrent.atomic.AtomicReference<Throwable> firstError =
                new java.util.concurrent.atomic.AtomicReference<>();
        Mono<TokenResponse> result = Mono.empty();
        for (String token : tokenValues) {
            final String currentToken = token;
            result = result.switchIfEmpty(
                authService.refreshAccessToken(currentToken, clientIp, userAgent)
                    .doOnNext(response -> {
                        authCookieService.addAccessTokenCookie(httpResponse, response.getAccessToken());
                        authCookieService.addRefreshTokenCookie(httpResponse, response.getRefreshToken(), true);
                    })
                    .onErrorResume(e -> {
                        firstError.compareAndSet(null, e);
                        if (tokenValues.size() > 1) {
                            log.warn("Refresh failed for one of {} cookies, trying next: {}",
                                    tokenValues.size(), e.getMessage());
                        }
                        return Mono.empty();
                    })
            );
        }

        // AUD18-L4: all cookies failed — rethrow the captured first error instead of
        // re-running the refresh (which would double-trigger token-theft detection).
        return result.switchIfEmpty(Mono.defer(() -> {
            Throwable captured = firstError.get();
            return Mono.error(captured != null
                    ? captured
                    : new IllegalArgumentException("error.refresh_token_required"));
        }));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(ServerHttpRequest httpRequest,
                              ServerHttpResponse httpResponse,
                              ServerWebExchange exchange) {
        log.info("Logout requested");
        List<String> refreshTokens = extractRefreshTokensFromCookies(httpRequest);
        String accessToken = extractAccessTokenFromCookie(httpRequest);

        // First blacklist/revoke tokens, THEN clear cookies, THEN rotate CSRF token
        Mono<Void> logoutMono = Mono.empty();
        if (!refreshTokens.isEmpty()) {
            String primaryRefreshToken = refreshTokens.get(0);
            logoutMono = authService.logout(primaryRefreshToken, accessToken)
                    .then(Flux.fromIterable(refreshTokens.subList(1, refreshTokens.size()))
                            .flatMap(refreshTokenService::revokeToken)
                            .then());
        } else if (accessToken != null) {
            logoutMono = authService.logout(null, accessToken);
        }
        return logoutMono
                .then(Mono.fromRunnable(() -> authCookieService.clearAuthCookies(httpResponse)))
                // Q4.2: Rotate CSRF token after auth state change
                .then(rotateCsrfToken(exchange))
                .onErrorResume(e -> {
                    log.warn("Logout blacklisting failed, clearing cookies anyway: {}", e.getMessage());
                    authCookieService.clearAuthCookies(httpResponse);
                    return rotateCsrfToken(exchange);
                });
    }

    // F-075: Explicitly require authentication (also enforced by SecurityConfig /api/v1/admin/auth/** rule)
    // AUD18-H1: JwtAuthenticationFilter sets a String principal (the email), never a
    // UserDetails — the old @AuthenticationPrincipal UserDetails parameter was always
    // null, so this endpoint answered valid:false for every authenticated call. Bind
    // the full Authentication instead: principal for the username, authorities for roles.
    @GetMapping("/verify")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public Mono<Map<String, Object>> verifyToken(org.springframework.security.core.Authentication authentication) {
        log.debug("Token verification requested");
        // FEAT-08: Return user info confirming token is valid
        if (authentication == null || authentication.getPrincipal() == null) {
            return Mono.just(Map.of("valid", false));
        }
        return Mono.just(Map.of(
            "valid", true,
            "username", String.valueOf(authentication.getPrincipal()),
            "roles", authentication.getAuthorities().stream()
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
    // AUD19C-C1B: add/clear cookie builders moved to security.AuthCookieService
    // (with their BUG-03/BUG-04 rationale) so MfaController and OAuth2Controller
    // share the exact same cookie attributes. Only the request-side readers remain.

    private List<String> extractRefreshTokensFromCookies(ServerHttpRequest request) {
        return request.getCookies()
                .getOrDefault(REFRESH_TOKEN_COOKIE, java.util.Collections.emptyList())
                .stream()
                .map(HttpCookie::getValue)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private String extractAccessTokenFromCookie(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(ACCESS_TOKEN_COOKIE);
        return cookie != null ? cookie.getValue() : null;
    }

    @GetMapping("/verify-email-change")
    public Mono<ResponseEntity<Map<String, String>>> verifyEmailChange(
            @RequestParam @NotBlank @Size(min = 1, max = 128) String token) {
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

    @GetMapping("/verify-email")
    public Mono<ResponseEntity<Map<String, String>>> verifyEmail(
            @RequestParam @NotBlank @Size(min = 1, max = 128) String token) {
        return emailVerificationService.verify(token)
                .map(userId -> ResponseEntity.ok(Map.of("message", "email.verified")));
    }

    @PostMapping("/resend-verification")
    public Mono<ResponseEntity<Map<String, String>>> resendVerification(
            @AuthenticationPrincipal String email) {
        return emailVerificationService.sendVerification(email)
                .thenReturn(ResponseEntity.accepted().body(Map.of("message", "email.verification_sent")));
    }

    @GetMapping("/revert-email-change")
    public Mono<ResponseEntity<Map<String, String>>> revertEmailChange(
            @RequestParam @NotBlank @Size(min = 1, max = 128) String token) {
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
        return authService.resolveUserIdByEmail(email)
                .flatMapMany(refreshTokenService::getActiveSessions)
                .map(session -> {
                    Map<String, Object> dto = new java.util.LinkedHashMap<>();
                    // AUD19C-SESSID: session ids are Snowflakes (> 2^53) — serialize as a
                    // string so JS clients don't silently round them and revoke the wrong
                    // session. DELETE /sessions/{id} keeps @PathVariable Long: Spring parses
                    // the string path segment back to the exact long.
                    dto.put("id", String.valueOf(session.getId()));
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
        return authService.resolveUserIdByEmail(email)
                .flatMap(userId -> refreshTokenService.revokeTokenById(sessionId, userId))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @DeleteMapping("/sessions")
    public Mono<ResponseEntity<Void>> revokeAllOtherSessions(
            @AuthenticationPrincipal String email,
            ServerHttpRequest httpRequest) {
        // The current session's refresh token identifies which session to preserve.
        String currentRefreshToken = extractRefreshTokensFromCookies(httpRequest)
                .stream().findFirst().orElse(null);
        if (currentRefreshToken == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return authService.resolveUserIdByEmail(email)
                .flatMap(userId -> refreshTokenService.revokeAllExceptCurrent(userId, currentRefreshToken))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
