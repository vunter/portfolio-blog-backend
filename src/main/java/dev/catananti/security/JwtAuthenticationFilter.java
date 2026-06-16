package dev.catananti.security;

import dev.catananti.entity.User;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.TokenBlacklistService;
import dev.catananti.service.UserCacheService;
import java.nio.charset.StandardCharsets;
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
import reactor.core.scheduler.Schedulers;

@Component
@Slf4j
public class JwtAuthenticationFilter implements WebFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserCacheService userCacheService;

    private static final String ACCESS_TOKEN_COOKIE = "access_token";

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, UserRepository userRepository,
                                   TokenBlacklistService tokenBlacklistService, UserCacheService userCacheService) {
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.tokenBlacklistService = tokenBlacklistService;
        this.userCacheService = userCacheService;
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

    /** Clear the invalid/expired access_token cookie from the browser */
    private void clearAccessTokenCookie(ServerWebExchange exchange) {
        exchange.getResponse().addCookie(
                ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                        .path("/api")
                        .maxAge(0)
                        .httpOnly(true)
                        .build());
    }

    /** Build a 401 Unauthorized JSON response with generic message (Q3.12: no detail leakage) */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":\"Unauthorized\",\"message\":\"Authentication failed\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    public static final String AUTHENTICATED_USER_ATTR = "authenticatedUser";

    /**
     * F-046: Proactively evict a user from the authentication cache.
     * @deprecated Use {@link UserCacheService#evict(Long)} directly instead.
     */
    @Deprecated
    public void evictUserFromCache(Long userId) {
        userCacheService.evict(userId);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String jwt = getJwtFromRequest(exchange);
        if (!StringUtils.hasText(jwt)) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();

        // F-ASYNC-01: Offload JWT crypto (HMAC-SHA512) from Netty event loop
        return Mono.fromCallable(() -> tokenProvider.validateAndParseClaims(jwt))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(validation -> {

        if (!validation.valid()) {
            // Clear the invalid/expired cookie so the browser stops sending it
            clearAccessTokenCookie(exchange);
            log.warn("Ignoring invalid JWT for path {}: {}", path, validation.error());
            return chain.filter(exchange);
        }

        // Token is valid (signature, encoding, and expiration all verified)
        var claims = validation.claims();
        String jti = claims.getId();
        String role = claims.get("role", String.class);
        Long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            log.warn("Access denied — JWT subject is not a numeric user id");
            clearAccessTokenCookie(exchange);
            return chain.filter(exchange);
        }

        // SEG-10: Fail safe on a missing jti. The logout blacklist is keyed by jti, so a
        // validly-signed token without one could never be revoked and would silently bypass
        // the blacklist. Treat that as suspicious: clear the cookie and continue UNAUTHENTICATED
        // rather than authenticating. The happy path below requires a present jti so the
        // blacklist check is always enforced.
        if (!StringUtils.hasText(jti)) {
            clearAccessTokenCookie(exchange);
            log.warn("Ignoring JWT with missing jti (cannot enforce logout blacklist) for path: {}", path);
            return chain.filter(exchange);
        }

        // Check if the token has been blacklisted (e.g. after logout)
        final Long uid = userId;
        return tokenBlacklistService.isBlacklisted(jti)
                .flatMap(blacklisted -> {
                    if (blacklisted) {
                        clearAccessTokenCookie(exchange);
                        log.warn("Ignoring blacklisted JWT for path: {}", path);
                        return chain.filter(exchange);
                    }
                    return authenticateUser(exchange, chain, uid, role);
                });
        }); // end Mono.fromCallable().flatMap()
    }

    /** F-044: Allowed roles whitelist to prevent arbitrary role injection */
    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "DEV", "VIEWER");

    private Mono<Void> authenticateUser(ServerWebExchange exchange, WebFilterChain chain, Long userId, String role) {
        // F-044: Validate role against whitelist
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            log.warn("Access denied — invalid role '{}' for userId={}", role, userId);
            return unauthorizedResponse(exchange, "Invalid role");
        }

        // Try Caffeine cache first, fall back to DB (lookup by ID — JWT sub is opaque, no PII)
        User cached = userCacheService.getIfPresent(userId);
        Mono<User> userMono = cached != null
                ? Mono.just(cached)
                : userRepository.findById(userId)
                        .doOnNext(u -> userCacheService.put(userId, u));

        return userMono
                .filter(user -> Boolean.TRUE.equals(user.getActive()))
                // F-045: Deny access when user is not found or inactive (must be BEFORE flatMap
                // because chain.filter() returns Mono<Void> which completes empty by design,
                // and switchIfEmpty after flatMap would always trigger on Mono<Void> completion)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Access denied — userId={} not found or inactive", userId);
                    return unauthorizedResponse(exchange, "User not found or inactive")
                            .then(Mono.empty());
                }))
                .flatMap(user -> {
                    log.debug("Authentication successful for userId={}", userId);
                    exchange.getAttributes().put(AUTHENTICATED_USER_ATTR, user);
                    // Principal stays as email so existing @AuthenticationPrincipal String email
                    // controllers keep working — sourced from the loaded user, not the JWT.
                    var auth = new UsernamePasswordAuthenticationToken(
                            user.getEmail(), null,
                            Collections.singleton(new SimpleGrantedAuthority("ROLE_" + role)));
                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                });
    }
}
