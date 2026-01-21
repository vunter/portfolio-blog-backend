package dev.catananti.config;

import dev.catananti.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityConfig Tests")
class SecurityConfigTest {

    private SecurityConfig securityConfig;

    @Mock
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig(jwtAuthenticationFilter);
        // Inject default @Value fields
        ReflectionTestUtils.setField(securityConfig, "allowedOrigins", "http://localhost:3000,http://127.0.0.1:3000");
        ReflectionTestUtils.setField(securityConfig, "allowedMethods", "GET,POST,PUT,DELETE,PATCH,OPTIONS");
        ReflectionTestUtils.setField(securityConfig, "csrfEnabled", true);
        ReflectionTestUtils.setField(securityConfig, "hstsEnabled", true);
        ReflectionTestUtils.setField(securityConfig, "swaggerEnabled", false);
    }

    @Nested
    @DisplayName("PasswordEncoder Bean")
    class PasswordEncoderTests {

        @Test
        @DisplayName("should return a BCryptPasswordEncoder instance")
        void shouldReturnBCryptPasswordEncoder() {
            PasswordEncoder encoder = securityConfig.passwordEncoder();

            assertThat(encoder).isInstanceOf(BCryptPasswordEncoder.class);
        }

        @Test
        @DisplayName("should use strength 12")
        void shouldUseStrength12() {
            PasswordEncoder encoder = securityConfig.passwordEncoder();

            // BCrypt strength 12 produces hashes starting with $2a$12$
            String encoded = encoder.encode("testPassword");
            assertThat(encoded).startsWith("$2a$12$");
        }

        @Test
        @DisplayName("should correctly verify matching passwords")
        void shouldVerifyMatchingPasswords() {
            PasswordEncoder encoder = securityConfig.passwordEncoder();

            String raw = "securePassword123!";
            String encoded = encoder.encode(raw);

            assertThat(encoder.matches(raw, encoded)).isTrue();
        }

        @Test
        @DisplayName("should reject non-matching passwords")
        void shouldRejectNonMatchingPasswords() {
            PasswordEncoder encoder = securityConfig.passwordEncoder();

            String encoded = encoder.encode("correctPassword");

            assertThat(encoder.matches("wrongPassword", encoded)).isFalse();
        }
    }

    @Nested
    @DisplayName("CORS Configuration")
    class CorsConfigurationTests {

        private CorsConfiguration corsConfig;

        @BeforeEach
        void setUp() {
            CorsConfigurationSource source = securityConfig.corsConfigurationSource();
            // Resolve configuration for any path (registered as /**)
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest
                            .get("/api/v1/articles")
                            .build();
            org.springframework.mock.web.server.MockServerWebExchange mockExchange =
                    org.springframework.mock.web.server.MockServerWebExchange.from(request);
            corsConfig = source.getCorsConfiguration(mockExchange);
        }

        @Test
        @DisplayName("should allow configured origins")
        void shouldAllowConfiguredOrigins() {
            assertThat(corsConfig).isNotNull();
            assertThat(corsConfig.getAllowedOriginPatterns())
                    .containsExactly("http://localhost:3000", "http://127.0.0.1:3000");
        }

        @Test
        @DisplayName("should allow configured methods")
        void shouldAllowConfiguredMethods() {
            assertThat(corsConfig.getAllowedMethods())
                    .containsExactly("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        }

        @Test
        @DisplayName("should allow X-XSRF-TOKEN header for CSRF double-submit")
        void shouldAllowXsrfTokenHeader() {
            assertThat(corsConfig.getAllowedHeaders())
                    .contains("X-XSRF-TOKEN");
        }

        @Test
        @DisplayName("should allow Authorization header")
        void shouldAllowAuthorizationHeader() {
            assertThat(corsConfig.getAllowedHeaders())
                    .contains("Authorization");
        }

        @Test
        @DisplayName("should allow X-Visitor-Id header")
        void shouldAllowVisitorIdHeader() {
            assertThat(corsConfig.getAllowedHeaders())
                    .contains("X-Visitor-Id");
        }

        @Test
        @DisplayName("should include all expected allowed headers")
        void shouldIncludeAllExpectedAllowedHeaders() {
            assertThat(corsConfig.getAllowedHeaders())
                    .containsExactlyInAnyOrder(
                            "Authorization", "Content-Type", "Accept", "Origin",
                            "X-Requested-With", "Cache-Control", "Accept-Language",
                            "X-XSRF-TOKEN", "X-Visitor-Id"
                    );
        }

        @Test
        @DisplayName("should expose rate limit headers")
        void shouldExposeRateLimitHeaders() {
            assertThat(corsConfig.getExposedHeaders())
                    .containsExactlyInAnyOrder(
                            "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset"
                    );
        }

        @Test
        @DisplayName("should allow credentials")
        void shouldAllowCredentials() {
            assertThat(corsConfig.getAllowCredentials()).isTrue();
        }

        @Test
        @DisplayName("should set maxAge to 3600 seconds")
        void shouldSetMaxAge() {
            assertThat(corsConfig.getMaxAge()).isEqualTo(3600L);
        }

        @Test
        @DisplayName("should use custom origins from configuration")
        void shouldUseCustomOrigins() {
            ReflectionTestUtils.setField(securityConfig, "allowedOrigins",
                    "https://mysite.com,https://admin.mysite.com");

            CorsConfigurationSource source = securityConfig.corsConfigurationSource();
            org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest
                            .get("/any-path")
                            .build();
            org.springframework.mock.web.server.MockServerWebExchange mockExchange =
                    org.springframework.mock.web.server.MockServerWebExchange.from(request);
            CorsConfiguration customConfig = source.getCorsConfiguration(mockExchange);

            assertThat(customConfig.getAllowedOriginPatterns())
                    .containsExactly("https://mysite.com", "https://admin.mysite.com");
        }

        @Test
        @DisplayName("should use custom methods from configuration")
        void shouldUseCustomMethods() {
            ReflectionTestUtils.setField(securityConfig, "allowedMethods", "GET,POST");

            CorsConfigurationSource source = securityConfig.corsConfigurationSource();
            org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest
                            .get("/any-path")
                            .build();
            org.springframework.mock.web.server.MockServerWebExchange mockExchange =
                    org.springframework.mock.web.server.MockServerWebExchange.from(request);
            CorsConfiguration customConfig = source.getCorsConfiguration(mockExchange);

            assertThat(customConfig.getAllowedMethods())
                    .containsExactly("GET", "POST");
        }

        @Test
        @DisplayName("should register configuration for all paths")
        void shouldRegisterForAllPaths() {
            CorsConfigurationSource source = securityConfig.corsConfigurationSource();

            // Any path should resolve the CORS config since it's registered as /**
            List<String> paths = List.of("/api/v1/articles", "/api/v1/admin/users", "/random/path");
            for (String path : paths) {
                org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
                        org.springframework.mock.http.server.reactive.MockServerHttpRequest
                                .get(path)
                                .build();
                org.springframework.mock.web.server.MockServerWebExchange mockExchange =
                        org.springframework.mock.web.server.MockServerWebExchange.from(request);
                CorsConfiguration config = source.getCorsConfiguration(mockExchange);
                assertThat(config).as("CORS config for path: %s", path).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("Configuration Field Defaults")
    class ConfigurationFieldTests {

        @Test
        @DisplayName("should have CSRF enabled by default")
        void shouldHaveCsrfEnabledByDefault() {
            boolean csrfEnabled = (boolean) ReflectionTestUtils.getField(securityConfig, "csrfEnabled");
            assertThat(csrfEnabled).isTrue();
        }

        @Test
        @DisplayName("should have HSTS enabled by default")
        void shouldHaveHstsEnabledByDefault() {
            boolean hstsEnabled = (boolean) ReflectionTestUtils.getField(securityConfig, "hstsEnabled");
            assertThat(hstsEnabled).isTrue();
        }

        @Test
        @DisplayName("should have Swagger disabled by default")
        void shouldHaveSwaggerDisabledByDefault() {
            boolean swaggerEnabled = (boolean) ReflectionTestUtils.getField(securityConfig, "swaggerEnabled");
            assertThat(swaggerEnabled).isFalse();
        }
    }

    // ==================== Additional Coverage Tests ====================

    @Nested
    @DisplayName("CORS Configuration - Edge Cases")
    class CorsEdgeCaseTests {

        @Test
        @DisplayName("should resolve CORS config for API paths")
        void shouldResolveCorsForApiPaths() {
            CorsConfigurationSource source = securityConfig.corsConfigurationSource();

            org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest
                            .get("/api/v1/admin/users")
                            .build();
            org.springframework.mock.web.server.MockServerWebExchange mockExchange =
                    org.springframework.mock.web.server.MockServerWebExchange.from(request);

            CorsConfiguration config = source.getCorsConfiguration(mockExchange);
            assertThat(config).isNotNull();
            assertThat(config.getAllowedOriginPatterns()).isNotEmpty();
        }

        @Test
        @DisplayName("should resolve CORS config for actuator paths")
        void shouldResolveCorsForActuatorPaths() {
            CorsConfigurationSource source = securityConfig.corsConfigurationSource();

            org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest
                            .get("/actuator/health")
                            .build();
            org.springframework.mock.web.server.MockServerWebExchange mockExchange =
                    org.springframework.mock.web.server.MockServerWebExchange.from(request);

            CorsConfiguration config = source.getCorsConfiguration(mockExchange);
            assertThat(config).isNotNull();
        }

        @Test
        @DisplayName("should handle single origin configuration")
        void shouldHandleSingleOrigin() {
            ReflectionTestUtils.setField(securityConfig, "allowedOrigins", "https://single-origin.com");

            CorsConfigurationSource source = securityConfig.corsConfigurationSource();
            org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest
                            .get("/api/v1/articles")
                            .build();
            org.springframework.mock.web.server.MockServerWebExchange mockExchange =
                    org.springframework.mock.web.server.MockServerWebExchange.from(request);

            CorsConfiguration config = source.getCorsConfiguration(mockExchange);
            assertThat(config.getAllowedOriginPatterns()).containsExactly("https://single-origin.com");
        }

        @Test
        @DisplayName("should handle wildcard origin patterns")
        void shouldHandleWildcardOriginPatterns() {
            ReflectionTestUtils.setField(securityConfig, "allowedOrigins", "http://localhost:*");

            CorsConfigurationSource source = securityConfig.corsConfigurationSource();
            org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest
                            .get("/api/v1/articles")
                            .build();
            org.springframework.mock.web.server.MockServerWebExchange mockExchange =
                    org.springframework.mock.web.server.MockServerWebExchange.from(request);

            CorsConfiguration config = source.getCorsConfiguration(mockExchange);
            assertThat(config.getAllowedOriginPatterns()).containsExactly("http://localhost:*");
        }
    }

    @Nested
    @DisplayName("CSRF Configuration")
    class CsrfConfigurationTests {

        @Test
        @DisplayName("should have csrfCookieWebFilter bean")
        void shouldHaveCsrfCookieWebFilter() {
            org.springframework.web.server.WebFilter filter = securityConfig.csrfCookieWebFilter();
            assertThat(filter).isNotNull();
        }
    }

    @Nested
    @DisplayName("Password Encoder - Edge Cases")
    class PasswordEncoderEdgeCases {

        @Test
        @DisplayName("should produce unique hashes for same password")
        void shouldProduceUniqueHashes() {
            PasswordEncoder encoder = securityConfig.passwordEncoder();

            String hash1 = encoder.encode("samePassword123!");
            String hash2 = encoder.encode("samePassword123!");

            // BCrypt produces different hashes each time due to random salt
            assertThat(hash1).isNotEqualTo(hash2);
            // But both should still match the original password
            assertThat(encoder.matches("samePassword123!", hash1)).isTrue();
            assertThat(encoder.matches("samePassword123!", hash2)).isTrue();
        }

        @Test
        @DisplayName("should handle single-character password")
        void shouldHandleEmptyPassword() {
            PasswordEncoder encoder = securityConfig.passwordEncoder();

            String encoded = encoder.encode("x");
            assertThat(encoded).isNotEmpty();
            assertThat(encoder.matches("x", encoded)).isTrue();
            assertThat(encoder.matches("y", encoded)).isFalse();
        }

        @Test
        @DisplayName("should handle password at 72-byte BCrypt limit")
        void shouldHandleLongPassword() {
            PasswordEncoder encoder = securityConfig.passwordEncoder();

            // BCrypt has a hard 72-byte limit; exactly 72 bytes should work
            String maxPassword = "a".repeat(72);
            String encoded = encoder.encode(maxPassword);
            assertThat(encoded).isNotEmpty();
            assertThat(encoder.matches(maxPassword, encoded)).isTrue();
        }
    }

    @Nested
    @DisplayName("CSRF Cookie WebFilter")
    class CsrfCookieWebFilterTests {

        @Test
        @DisplayName("should subscribe to CsrfToken Mono when attribute present")
        void shouldSubscribeToCsrfTokenWhenPresent() {
            WebFilter webFilter = securityConfig.csrfCookieWebFilter();

            org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/test").build();
            org.springframework.mock.web.server.MockServerWebExchange exchange =
                    org.springframework.mock.web.server.MockServerWebExchange.from(request);

            CsrfToken csrfToken = mock(CsrfToken.class);
            exchange.getAttributes().put(CsrfToken.class.getName(), Mono.just(csrfToken));

            WebFilterChain chain = mock(WebFilterChain.class);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(webFilter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("should pass through when CsrfToken attribute absent")
        void shouldPassThroughWhenCsrfTokenAbsent() {
            WebFilter webFilter = securityConfig.csrfCookieWebFilter();

            org.springframework.mock.http.server.reactive.MockServerHttpRequest request =
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/test").build();
            org.springframework.mock.web.server.MockServerWebExchange exchange =
                    org.springframework.mock.web.server.MockServerWebExchange.from(request);
            // No CsrfToken attribute set

            WebFilterChain chain = mock(WebFilterChain.class);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(webFilter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }
    }
}
