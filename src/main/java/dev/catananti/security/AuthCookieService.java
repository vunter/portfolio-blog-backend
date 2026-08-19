package dev.catananti.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * AUD19C-C1B: single source of truth for the auth cookie contract.
 *
 * <p>Extracted from {@code AuthController}'s private helpers so that every
 * endpoint that establishes a session — password login, register, refresh,
 * OAuth2 callback and the MFA login completion ({@code /admin/mfa/verify}) —
 * emits byte-identical cookies. Before this extraction the MFA completion set
 * NO cookies at all (the login never materialized as a session) and the OAuth2
 * callback carried a drifted inline copy.
 */
@Component
@Slf4j
public class AuthCookieService {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    // BUG-04: All auth cookies MUST use SameSite=Lax to match OAuth2Controller.
    // Using Strict here while OAuth uses Lax can cause browsers to maintain duplicate
    // cookies (one Lax from OAuth, one Strict from refresh), and getFirst() reads the
    // stale one — causing "revoked token reuse" failures after every rotation.
    // Lax is safe: it prevents cross-site POST/fetch from sending cookies (CSRF protection)
    // while allowing same-site AJAX and top-level navigations.
    private static final String COOKIE_SAME_SITE = "Lax";

    private final long jwtExpirationMs;
    private final boolean cookieSecure;
    private final String cookieDomain;

    public AuthCookieService(@Value("${jwt.expiration:86400000}") long jwtExpirationMs,
                             @Value("${jwt.cookie.secure:true}") boolean cookieSecure,
                             @Value("${jwt.cookie.domain:}") String cookieDomain) {
        this.jwtExpirationMs = jwtExpirationMs;
        this.cookieSecure = cookieSecure;
        this.cookieDomain = cookieDomain;
    }

    public void addAccessTokenCookie(ServerHttpResponse response, String token) {
        // AUD19C-C1C: defense in depth — never emit an empty auth cookie. A blank
        // token here means the caller is on a path that has no session to establish
        // (e.g. an MFA challenge response); writing "" would clobber a valid cookie.
        if (!StringUtils.hasText(token)) {
            log.warn("Skipped setting {} cookie: blank token", ACCESS_TOKEN_COOKIE);
            return;
        }
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

    public void addRefreshTokenCookie(ServerHttpResponse response, String token, boolean rememberMe) {
        // AUD19C-C1C: same blank-token guard as the access cookie.
        if (!StringUtils.hasText(token)) {
            log.warn("Skipped setting {} cookie: blank token", REFRESH_TOKEN_COOKIE);
            return;
        }
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

    public void clearAuthCookies(ServerHttpResponse response) {
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
}
