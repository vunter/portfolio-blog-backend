package dev.catananti.controller;

import dev.catananti.dto.AuthResponse;
import dev.catananti.dto.LoginRequest;
import dev.catananti.dto.RegisterRequest;
import dev.catananti.dto.TokenResponse;
import dev.catananti.service.AuthService;
import dev.catananti.service.RecaptchaService;
import dev.catananti.util.IpAddressExtractor;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
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

    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationMs;

    @Value("${jwt.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${jwt.cookie.domain:}")
    private String cookieDomain;

    private static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    // F-076: Rate-limited via nginx login zone (5r/m)
    // TODO F-077: Enforce password complexity (uppercase, lowercase, digit, special char) on registration/reset
    @PostMapping("/login")
    public Mono<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                     ServerHttpRequest httpRequest,
                                     ServerHttpResponse httpResponse) {
        log.info("Login attempt for user='{}' from ip={}", request.getEmail(), IpAddressExtractor.extractClientIp(httpRequest));
        String clientIp = IpAddressExtractor.extractClientIp(httpRequest);
        return recaptchaService.verify(request.getRecaptchaToken(), "login")
                .then(authService.login(request, clientIp))
                .doOnNext(response -> {
                    addAccessTokenCookie(httpResponse, response.getToken());
                });
    }

    @PostMapping("/login/v2")
    public Mono<TokenResponse> loginV2(@Valid @RequestBody LoginRequest request,
                                        ServerHttpRequest httpRequest,
                                        ServerHttpResponse httpResponse) {
        log.info("Login V2 attempt for user='{}' from ip={}", request.getEmail(), IpAddressExtractor.extractClientIp(httpRequest));
        String clientIp = IpAddressExtractor.extractClientIp(httpRequest);
        return recaptchaService.verify(request.getRecaptchaToken(), "login")
                .then(authService.loginWithRefreshToken(request, clientIp))
                .doOnNext(response -> {
                    addAccessTokenCookie(httpResponse, response.getAccessToken());
                    addRefreshTokenCookie(httpResponse, response.getRefreshToken(), Boolean.TRUE.equals(request.getRememberMe()));
                });
    }

    // F-076: Rate-limited via nginx login zone (5r/m)
    @PostMapping("/register")
    public Mono<ResponseEntity<TokenResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            ServerHttpRequest httpRequest,
            ServerHttpResponse httpResponse) {
        log.info("Registration attempt for user='{}' from ip={}", request.email(), IpAddressExtractor.extractClientIp(httpRequest));
        String clientIp = IpAddressExtractor.extractClientIp(httpRequest);
        return recaptchaService.verify(request.recaptchaToken(), "register")
                .then(authService.register(request, clientIp))
                .map(tokenResponse -> {
                    addAccessTokenCookie(httpResponse, tokenResponse.getAccessToken());
                    addRefreshTokenCookie(httpResponse, tokenResponse.getRefreshToken(), false);
                    return ResponseEntity.status(HttpStatus.CREATED).body(tokenResponse);
                });
    }

    @PostMapping("/refresh")
    public Mono<TokenResponse> refreshToken(ServerHttpRequest httpRequest,
                                             ServerHttpResponse httpResponse) {
        log.info("Token refresh requested");
        // F-074: Fully reactive — no blocking null check before reactive chain
        return Mono.justOrEmpty(httpRequest.getCookies().getFirst(REFRESH_TOKEN_COOKIE))
                .map(HttpCookie::getValue)
                .filter(StringUtils::hasText)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Refresh token is required")))
                .flatMap(refreshToken -> authService.refreshAccessToken(refreshToken)
                        .doOnNext(response -> {
                            addAccessTokenCookie(httpResponse, response.getAccessToken());
                            addRefreshTokenCookie(httpResponse, response.getRefreshToken(), true);
                        }));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(ServerHttpRequest httpRequest,
                              ServerHttpResponse httpResponse) {
        log.info("Logout requested");
        // Extract tokens from cookies
        String refreshToken = extractRefreshTokenFromCookie(httpRequest);
        String accessToken = extractAccessTokenFromCookie(httpRequest);

        // Clear cookies immediately
        clearAuthCookies(httpResponse);

        if (refreshToken != null) {
            return authService.logout(refreshToken, accessToken);
        }
        // Even without refresh token, blacklist the access token
        if (accessToken != null) {
            return authService.logout(null, accessToken);
        }
        return Mono.empty();
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

    // ===== Cookie Helpers =====

    private void addAccessTokenCookie(ServerHttpResponse response, String token) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(ACCESS_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api")
                .maxAge(Duration.ofMillis(jwtExpirationMs))
                .sameSite("Strict");
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
                .sameSite("Strict");
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        response.addCookie(builder.build());
    }

    private void clearAuthCookies(ServerHttpResponse response) {
        ResponseCookie.ResponseCookieBuilder accessBuilder = ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api")
                .maxAge(0)
                .sameSite("Strict");
        ResponseCookie.ResponseCookieBuilder refreshBuilder = ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/api/v1/admin/auth")
                .maxAge(0)
                .sameSite("Strict");
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            accessBuilder.domain(cookieDomain);
            refreshBuilder.domain(cookieDomain);
        }
        response.addCookie(accessBuilder.build());
        response.addCookie(refreshBuilder.build());
    }

    private String extractRefreshTokenFromCookie(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(REFRESH_TOKEN_COOKIE);
        return cookie != null ? cookie.getValue() : null;
    }

    private String extractAccessTokenFromCookie(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(ACCESS_TOKEN_COOKIE);
        return cookie != null ? cookie.getValue() : null;
    }
}
