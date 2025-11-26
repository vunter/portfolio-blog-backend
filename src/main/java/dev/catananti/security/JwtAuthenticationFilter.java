package dev.catananti.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.catananti.entity.User;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.TokenBlacklistService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class JwtAuthenticationFilter implements WebFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * Short-lived Caffeine cache to avoid DB lookup on every authenticated request.
     * F-046: 60s TTL is a security/performance tradeoff — deactivated users locked out within 1 minute
     */
    private final Cache<String, User> userCache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    private static final String ACCESS_TOKEN_COOKIE = "access_token";

    /** Auth endpoints exempt from token rejection (needed for refresh/login/logout flow) */
    private static final Set<String> AUTH_EXEMPT_PATHS = Set.of(
            "/api/v1/admin/auth/login",
            "/api/v1/admin/auth/login/v2",
            "/api/v1/admin/auth/register",
            "/api/v1/admin/auth/refresh",
            "/api/v1/admin/auth/logout"
    );

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, UserRepository userRepository, TokenBlacklistService tokenBlacklistService) {
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    private String getJwtFromRequest(ServerWebExchange exchange) {
        // 1. Try Authorization header first
        String bearerToken = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // 2. Fall back to HttpOnly cookie
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(ACCESS_TOKEN_COOKIE);
        if (cookie != null && StringUtils.hasText(cookie.getValue())) {
            return cookie.getValue();
        }
        return null;
    }

    /** Check if the request path is exempt from token rejection (auth + public endpoints) */
    private boolean isExemptPath(String path) {
        return AUTH_EXEMPT_PATHS.contains(path) || path.startsWith("/api/v1/public/");
    }

    /** Clear the invalid/expired access_token cookie from the browser */
    private void clearAccessTokenCookie(ServerWebExchange exchange) {
        exchange.getResponse().addCookie(
                ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                        .path("/api")
                        .maxAge(0)
                        .httpOnly(true)
                        .build());
    }

    /** Build a 401 Unauthorized JSON response (F-043: proper JSON escaping to prevent injection) */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // Escape JSON special characters to prevent injection
        String safeMessage = message.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        String body = "{\"error\":\"Unauthorized\",\"message\":\"" + safeMessage + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    public static final String AUTHENTICATED_USER_ATTR = "authenticatedUser";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String jwt = getJwtFromRequest(exchange);
        if (!StringUtils.hasText(jwt)) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        var validation = tokenProvider.validateAndParseClaims(jwt);

        if (!validation.valid()) {
            // Clear the invalid/expired cookie so the browser stops sending it
            clearAccessTokenCookie(exchange);

            // Allow auth & public endpoints to proceed without authentication
            if (isExemptPath(path)) {
                return chain.filter(exchange);
            }

            // For protected routes: deny access immediately with 401
            log.warn("Access denied — {} for path: {}", validation.error(), path);
            return unauthorizedResponse(exchange, validation.error());
        }

        // Token is valid (signature, encoding, and expiration all verified)
        var claims = validation.claims();
        String jti = claims.getId();
        String email = claims.getSubject();
        String role = claims.get("role", String.class);

        // Check if the token has been blacklisted (e.g. after logout)
        if (jti != null) {
            return tokenBlacklistService.isBlacklisted(jti)
                    .flatMap(blacklisted -> {
                        if (blacklisted) {
                            clearAccessTokenCookie(exchange);
                            if (isExemptPath(path)) {
                                return chain.filter(exchange);
                            }
                            log.warn("Access denied — blacklisted token for path: {}", path);
                            return unauthorizedResponse(exchange, "Token revoked");
                        }
                        return authenticateUser(exchange, chain, email, role);
                    });
        }

        return authenticateUser(exchange, chain, email, role);
    }

    /** F-044: Allowed roles whitelist to prevent arbitrary role injection */
    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "DEV", "EDITOR", "VIEWER");

    private Mono<Void> authenticateUser(ServerWebExchange exchange, WebFilterChain chain, String email, String role) {
        // F-044: Validate role against whitelist
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            log.warn("Access denied — invalid role '{}' for user: {}", role, email);
            return unauthorizedResponse(exchange, "Invalid role");
        }

        // Try Caffeine cache first, fall back to DB
        User cached = userCache.getIfPresent(email);
        Mono<User> userMono = cached != null
                ? Mono.just(cached)
                : userRepository.findByEmail(email)
                        .doOnNext(u -> userCache.put(email, u));

        return userMono
                .filter(user -> Boolean.TRUE.equals(user.getActive()))
                // F-045: Deny access when user is not found or inactive (must be BEFORE flatMap
                // because chain.filter() returns Mono<Void> which completes empty by design,
                // and switchIfEmpty after flatMap would always trigger on Mono<Void> completion)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Access denied — user not found or inactive: {}", email);
                    return unauthorizedResponse(exchange, "User not found or inactive")
                            .then(Mono.empty());
                }))
                .flatMap(user -> {
                    log.debug("Authentication successful for user: {}", email);
                    exchange.getAttributes().put(AUTHENTICATED_USER_ATTR, user);
                    var auth = new UsernamePasswordAuthenticationToken(
                            email, null,
                            Collections.singleton(new SimpleGrantedAuthority("ROLE_" + role)));
                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                });
    }
}
