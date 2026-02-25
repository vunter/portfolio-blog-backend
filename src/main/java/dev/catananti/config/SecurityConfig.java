package dev.catananti.config;

import dev.catananti.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
    private String allowedOrigins;

    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,PATCH,OPTIONS}")
    private String allowedMethods;

    @Value("${security.csrf.enabled:true}")
    private boolean csrfEnabled;

    @Value("${security.hsts.enabled:true}")
    private boolean hstsEnabled;

    @Value("${springdoc.swagger-ui.enabled:false}")
    private boolean swaggerEnabled;

    // F-016: Single source of truth for public auth paths — used by both CSRF exemption and authorize rules
    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/v1/admin/auth/login",
            "/api/v1/admin/auth/login/v2",
            "/api/v1/admin/auth/register",
            "/api/v1/admin/auth/refresh",
            "/api/v1/admin/auth/logout",
            "/api/v1/admin/auth/forgot-password",
            "/api/v1/admin/auth/reset-password",
            "/api/v1/admin/auth/reset-password/validate",
            "/api/v1/admin/mfa/verify",
            "/api/v1/admin/mfa/send-email-otp"
    );

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        log.info("Configuring security filter chain");
        return http
                .csrf(csrf -> {
                    if (csrfEnabled) {
                        // Double-submit cookie pattern: XSRF-TOKEN cookie + X-XSRF-TOKEN header
                        CookieServerCsrfTokenRepository tokenRepository = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
                        ServerCsrfTokenRequestAttributeHandler handler = new ServerCsrfTokenRequestAttributeHandler();
                        csrf.csrfTokenRepository(tokenRepository)
                            .csrfTokenRequestHandler(handler)
                            // Exempt public API endpoints (reads, auth, newsletter, contact)
                            .requireCsrfProtectionMatcher(exchange -> {
                                String path = exchange.getRequest().getPath().value();
                                String method = exchange.getRequest().getMethod().name();
                                // Only require CSRF for state-changing methods
                                if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
                                    return org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult.notMatch();
                                }
                                // Exempt public endpoints that are protected by rate limiting
                                if (path.startsWith("/api/v1/articles/") || path.startsWith("/api/v1/newsletter/")
                                    || path.startsWith("/api/v1/contact") || path.startsWith("/api/v1/search/")) {
                                    return org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult.notMatch();
                                }
                                // F-015: CSRF exempt auth paths partially overlap with authorize matchers
                                // (e.g., /api/v1/analytics/**, /api/v1/bookmarks/** are permitAll but not CSRF-exempt for POST)
                                // F-016: Extracted to shared constant PUBLIC_AUTH_PATHS
                                if (PUBLIC_AUTH_PATHS.contains(path)) {
                                    return org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult.notMatch();
                                }
                                return org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult.match();
                            });
                    } else {
                        csrf.disable();
                    }
                })
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Disable HTTP Basic and form login — this is a stateless JWT API
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                // Custom 401 response: return JSON without WWW-Authenticate header
                // to prevent browsers from showing a native Basic Auth credentials dialog
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((exchange, ex) -> {
                            log.warn("Unauthorized access attempt: {}", exchange.getRequest().getPath());
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            String body = "{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}";
                            DataBuffer buffer = exchange.getResponse().bufferFactory()
                                    .wrap(body.getBytes(StandardCharsets.UTF_8));
                            return exchange.getResponse().writeWith(Mono.just(buffer));
                        })
                )
                // Security Headers
                .headers(headers -> headers
                        // SEC-09: Use DENY consistently — nginx should match (remove its SAMEORIGIN)
                        .frameOptions(frame -> frame.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        // SEC-07: Static CSP removed — CspNonceFilter provides nonce-based CSP dynamically
                        // HSTS - Enable for production (HTTPS)
                        .hsts(hsts -> hsts
                                .includeSubdomains(true)
                                .maxAge(java.time.Duration.ofDays(365))
                                .preload(true)
                        )
                        // Referrer Policy - Prevent URL leakage
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                        )
                        // X-Content-Type-Options - Prevent MIME sniffing
                        .contentTypeOptions(contentType -> {})
                        // Permissions Policy (formerly Feature Policy)
                        .permissionsPolicy(permissions -> permissions
                                .policy("geolocation=(), microphone=(), camera=(), payment=()")
                        )
                )
                // TODO F-017: Consider consolidating path matchers into a role-based map or enum for maintainability
                .authorizeExchange(auth -> auth
                        // Public article endpoints
                        .pathMatchers("/api/v1/articles/**").permitAll()
                        .pathMatchers("/api/v1/tags/**").permitAll()
                        .pathMatchers("/api/v1/search/**").permitAll()
                        .pathMatchers("/api/v1/newsletter/**").permitAll()
                        .pathMatchers("/api/v1/status/**").permitAll()
                        // F-016: Uses PUBLIC_AUTH_PATHS constant shared with CSRF exemption
                        .pathMatchers(PUBLIC_AUTH_PATHS.toArray(new String[0])).permitAll()
                        .pathMatchers("/api", "/api/v1", "/api/health").permitAll()
                        // Public resume endpoints
                        .pathMatchers("/api/v1/resume/css/**").permitAll()
                        .pathMatchers("/api/v1/resume/templates/popular").permitAll()
                        .pathMatchers("/api/v1/resume/templates/slug/*/pdf").permitAll()
                        // Public resume download
                        .pathMatchers("/api/public/**").permitAll()
                        .pathMatchers("/api/v1/public/**").permitAll()
                        // Bookmarks (anonymous visitors via X-Visitor-Id)
                        .pathMatchers("/api/v1/bookmarks/**").permitAll()
                        // Analytics event tracking (public)
                        .pathMatchers("/api/v1/analytics/**").permitAll()
                        // Contact form (public)
                        .pathMatchers(HttpMethod.POST, "/api/v1/contact").permitAll()
                        // Actuator - health/info/prometheus public (prometheus protected by nginx basic auth externally)
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/actuator/prometheus").permitAll()
                        .pathMatchers("/actuator/**").hasRole("ADMIN")
                        // Kubernetes probes
                        .pathMatchers("/livez", "/readyz").permitAll()
                        // Images (static content)
                        .pathMatchers("/images/**").permitAll()
                        // OpenAPI / Swagger UI — only permit when explicitly enabled
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/webjars/**")
                            .access((authentication, ctx) -> Mono.just(new org.springframework.security.authorization.AuthorizationDecision(swaggerEnabled)))
                        // RSS and Sitemap
                        .pathMatchers("/rss.xml", "/feed.xml", "/sitemap.xml").permitAll()
                        // Admin user management - /me/** for any authenticated user, rest ADMIN only
                        .pathMatchers("/api/v1/admin/users/me/**").authenticated()
                        .pathMatchers("/api/v1/admin/users/me").authenticated()
                        .pathMatchers("/api/v1/admin/users/**").hasRole("ADMIN")
                        // MFA management - any authenticated user can manage their own MFA
                        .pathMatchers("/api/v1/admin/mfa/setup", "/api/v1/admin/mfa/verify-setup",
                                      "/api/v1/admin/mfa/disable", "/api/v1/admin/mfa/status").authenticated()
                        // Admin cache, export, settings, newsletter management - ADMIN only
                        .pathMatchers("/api/v1/admin/cache/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/admin/export/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/admin/settings/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/admin/newsletter/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/admin/contact/**").hasRole("ADMIN")
                        // Content creation - ADMIN, DEV, and EDITOR roles
                        .pathMatchers("/api/v1/admin/articles/**").hasAnyRole("ADMIN", "DEV", "EDITOR")
                        .pathMatchers("/api/v1/admin/tags/**").hasAnyRole("ADMIN", "DEV", "EDITOR")
                        .pathMatchers("/api/v1/admin/comments/**").hasAnyRole("ADMIN", "DEV", "EDITOR")
                        .pathMatchers("/api/v1/admin/images/**").hasAnyRole("ADMIN", "DEV", "EDITOR")
                        .pathMatchers("/api/v1/admin/media/**").hasAnyRole("ADMIN", "DEV", "EDITOR")
                        // Resume profile - ADMIN and DEV
                        .pathMatchers("/api/v1/resume/profile/**").hasAnyRole("ADMIN", "DEV")
                        .pathMatchers("/api/v1/resume/templates/**").hasAnyRole("ADMIN", "DEV")
                        // Dashboard and analytics - ADMIN, DEV, and EDITOR (read-only)
                        .pathMatchers("/api/v1/admin/dashboard/**").hasAnyRole("ADMIN", "DEV", "EDITOR")
                        .pathMatchers("/api/v1/admin/analytics/**").hasAnyRole("ADMIN", "DEV", "EDITOR")
                        // Auth endpoints (verify, logout) require authentication
                        .pathMatchers("/api/v1/admin/auth/**").authenticated()
                        // All other admin endpoints require authentication
                        .pathMatchers("/api/v1/admin/**").authenticated()
                        .anyExchange().authenticated()
                )
                // NOTE F-018: Session fixation is not applicable — auth is stateless JWT (no server-side sessions)
                .addFilterAt(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    /**
     * BUG-01 FIX: Subscribe to the CsrfToken Mono so Spring Security 6+ WebFlux
     * actually writes the XSRF-TOKEN cookie. Without this, the lazy CsrfToken is
     * never resolved and the cookie is never set.
     */
    @Bean
    public WebFilter csrfCookieWebFilter() {
        return (exchange, chain) -> {
            Mono<CsrfToken> csrfToken = exchange.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                return csrfToken.then(chain.filter(exchange));
            }
            return chain.filter(exchange);
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        log.info("Configuring CORS");
        CorsConfiguration configuration = new CorsConfiguration();
        // TODO F-022: Ensure allowedOriginPatterns do not include broad wildcards like "*" in production
        // Use origin patterns to support wildcards like http://localhost:*
        List<String> origins = List.of(allowedOrigins.split(","));
        configuration.setAllowedOriginPatterns(origins);
        configuration.setAllowedMethods(List.of(allowedMethods.split(",")));
        // BUG-06: Added X-XSRF-TOKEN to allowed headers for CSRF double-submit cookie pattern
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Origin",
                "X-Requested-With", "Cache-Control", "Accept-Language", "X-XSRF-TOKEN", "X-Visitor-Id"
        ));
        configuration.setExposedHeaders(List.of(
                "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
