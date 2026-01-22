package dev.catananti.security;

import dev.catananti.entity.User;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Optional;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    private static final String VALID_JWT = "valid.jwt.token";
    private static final String INVALID_JWT = "invalid.jwt.token";
    private static final String TEST_EMAIL = "leonardo@example.com";
    private static final String TEST_ROLE = "ADMIN";

    private User activeUser;
    private User inactiveUser;

    @BeforeEach
    void setUp() {
        activeUser = new User();
        activeUser.setId(1L);
        activeUser.setEmail(TEST_EMAIL);
        activeUser.setName("Leonardo Catananti");
        activeUser.setRole(TEST_ROLE);
        activeUser.setActive(true);

        inactiveUser = new User();
        inactiveUser.setId(2L);
        inactiveUser.setEmail(TEST_EMAIL);
        inactiveUser.setName("Inactive User");
        inactiveUser.setRole("VIEWER");
        inactiveUser.setActive(false);
    }

    /**
     * Creates a WebFilterChain that captures the SecurityContext propagated
     * through Reactor context, so we can verify authentication was set.
     */
    private WebFilterChain capturingChain(SecurityContext[] holder) {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .doOnNext(ctx -> holder[0] = ctx)
                .then();
    }

    /**
     * Creates a simple pass-through WebFilterChain that completes immediately.
     */
    private WebFilterChain passThroughChain() {
        return exchange -> Mono.empty();
    }

    @Nested
    @DisplayName("Authorization Header JWT")
    class AuthorizationHeaderTests {

        @Test
        @DisplayName("Should authenticate when valid JWT in Authorization header")
        void shouldAuthenticateWithValidBearerToken() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaims(TEST_EMAIL, TEST_ROLE);
            when(tokenProvider.validateAndParseClaims(VALID_JWT)).thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Mono.just(activeUser));

            SecurityContext[] captured = new SecurityContext[1];
            WebFilterChain chain = capturingChain(captured);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getAuthentication()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
            assertThat(captured[0].getAuthentication().getName()).isEqualTo(TEST_EMAIL);

            verify(tokenProvider).validateAndParseClaims(VALID_JWT);
            verify(userRepository).findByEmail(TEST_EMAIL);
        }
    }

    @Nested
    @DisplayName("Cookie JWT")
    class CookieTests {

        @Test
        @DisplayName("Should authenticate when valid JWT in cookie")
        void shouldAuthenticateWithValidCookie() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .cookie(new HttpCookie("access_token", VALID_JWT))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaims(TEST_EMAIL, TEST_ROLE);
            when(tokenProvider.validateAndParseClaims(VALID_JWT)).thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Mono.just(activeUser));

            SecurityContext[] captured = new SecurityContext[1];
            WebFilterChain chain = capturingChain(captured);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getAuthentication().getName()).isEqualTo(TEST_EMAIL);

            verify(tokenProvider).validateAndParseClaims(VALID_JWT);
            verify(userRepository).findByEmail(TEST_EMAIL);
        }
    }

    @Nested
    @DisplayName("Header vs Cookie Priority")
    class PriorityTests {

        @Test
        @DisplayName("Should prefer Authorization header over cookie")
        void shouldPreferHeaderOverCookie() {
            // Given
            String headerJwt = "header.jwt.token";
            String cookieJwt = "cookie.jwt.token";

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + headerJwt)
                    .cookie(new HttpCookie("access_token", cookieJwt))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaims(TEST_EMAIL, TEST_ROLE);
            when(tokenProvider.validateAndParseClaims(headerJwt)).thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Mono.just(activeUser));

            // When
            Mono<Void> result = filter.filter(exchange, passThroughChain());

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            // Verify the header JWT was used, not the cookie JWT
            verify(tokenProvider).validateAndParseClaims(headerJwt);
            verify(tokenProvider, never()).validateAndParseClaims(cookieJwt);
        }
    }

    @Nested
    @DisplayName("No Authentication Scenarios")
    class NoAuthenticationTests {

        @Test
        @DisplayName("Should return 401 when JWT is invalid on protected route")
        void shouldNotAuthenticateWithInvalidJwt() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + INVALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(tokenProvider.validateAndParseClaims(INVALID_JWT)).thenReturn(JwtTokenProvider.TokenValidationResult.invalid("Invalid token"));

            // When
            Mono<Void> result = filter.filter(exchange, passThroughChain());

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            // Protected route with invalid token should return 401 immediately
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(tokenProvider).validateAndParseClaims(INVALID_JWT);
            verify(userRepository, never()).findByEmail(any());
        }

        @Test
        @DisplayName("Should not authenticate when no JWT provided")
        void shouldNotAuthenticateWithoutJwt() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            SecurityContext[] captured = new SecurityContext[1];
            WebFilterChain chain = capturingChain(captured);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            assertThat(captured[0]).isNull();
            verify(tokenProvider, never()).validateAndParseClaims(any());
            verify(userRepository, never()).findByEmail(any());
        }

        @Test
        @DisplayName("Should not authenticate when user not found in DB")
        void shouldNotAuthenticateWhenUserNotFound() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaims(TEST_EMAIL, TEST_ROLE);
            when(tokenProvider.validateAndParseClaims(VALID_JWT)).thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Mono.empty());

            SecurityContext[] captured = new SecurityContext[1];
            WebFilterChain chain = capturingChain(captured);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            assertThat(captured[0]).isNull();
            verify(userRepository).findByEmail(TEST_EMAIL);
        }

        @Test
        @DisplayName("Should not authenticate when user is inactive")
        void shouldNotAuthenticateWhenUserInactive() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaims(TEST_EMAIL, TEST_ROLE);
            when(tokenProvider.validateAndParseClaims(VALID_JWT)).thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Mono.just(inactiveUser));

            SecurityContext[] captured = new SecurityContext[1];
            WebFilterChain chain = capturingChain(captured);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            // Inactive user should be filtered out, so no SecurityContext is set
            assertThat(captured[0]).isNull();
            verify(userRepository).findByEmail(TEST_EMAIL);
        }
    }

    @Nested
    @DisplayName("Exchange Attributes")
    class ExchangeAttributeTests {

        @Test
        @DisplayName("Should store authenticated user in exchange attributes")
        void shouldStoreUserInExchangeAttributes() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaims(TEST_EMAIL, TEST_ROLE);
            when(tokenProvider.validateAndParseClaims(VALID_JWT)).thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Mono.just(activeUser));

            // When
            Mono<Void> result = filter.filter(exchange, passThroughChain());

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            Object storedUser = exchange.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_USER_ATTR);
            assertThat(storedUser).isNotNull();
            assertThat(storedUser).isInstanceOf(User.class);
            assertThat(((User) storedUser).getEmail()).isEqualTo(TEST_EMAIL);
            assertThat(((User) storedUser).getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should not store user in exchange attributes when no JWT")
        void shouldNotStoreUserWhenNoJwt() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            // When
            Mono<Void> result = filter.filter(exchange, passThroughChain());

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            assertThat((Object) exchange.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_USER_ATTR)).isNull();
        }

        @Test
        @DisplayName("Should not store user in exchange attributes when user inactive")
        void shouldNotStoreUserWhenInactive() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaims(TEST_EMAIL, TEST_ROLE);
            when(tokenProvider.validateAndParseClaims(VALID_JWT)).thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Mono.just(inactiveUser));

            // When
            Mono<Void> result = filter.filter(exchange, passThroughChain());

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            assertThat((Object) exchange.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_USER_ATTR)).isNull();
        }
    }

    @Nested
    @DisplayName("Authority / Role Mapping")
    class AuthorityTests {

        @Test
        @DisplayName("Should set correct ROLE_ authority from JWT for ADMIN")
        void shouldSetAdminRoleAuthority() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaims(TEST_EMAIL, "ADMIN");
            when(tokenProvider.validateAndParseClaims(VALID_JWT)).thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Mono.just(activeUser));

            SecurityContext[] captured = new SecurityContext[1];
            WebFilterChain chain = capturingChain(captured);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getAuthentication().getAuthorities())
                    .hasSize(1)
                    .extracting(Object::toString)
                    .containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("Should set correct ROLE_ authority from JWT for VIEWER")
        void shouldSetViewerRoleAuthority() {
            // Given
            User viewerUser = new User();
            viewerUser.setId(3L);
            viewerUser.setEmail("viewer@example.com");
            viewerUser.setName("Viewer User");
            viewerUser.setRole("VIEWER");
            viewerUser.setActive(true);

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaims("viewer@example.com", "VIEWER");
            when(tokenProvider.validateAndParseClaims(VALID_JWT)).thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(userRepository.findByEmail("viewer@example.com")).thenReturn(Mono.just(viewerUser));

            SecurityContext[] captured = new SecurityContext[1];
            WebFilterChain chain = capturingChain(captured);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getAuthentication().getAuthorities())
                    .hasSize(1)
                    .extracting(Object::toString)
                    .containsExactly("ROLE_VIEWER");
        }

        @Test
        @DisplayName("Should set principal to email from JWT")
        void shouldSetPrincipalToEmail() {
            // Given
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaims(TEST_EMAIL, TEST_ROLE);
            when(tokenProvider.validateAndParseClaims(VALID_JWT)).thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Mono.just(activeUser));

            SecurityContext[] captured = new SecurityContext[1];
            WebFilterChain chain = capturingChain(captured);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();

            assertThat(captured[0].getAuthentication().getPrincipal()).isEqualTo(TEST_EMAIL);
            assertThat(captured[0].getAuthentication().getCredentials()).isNull();
        }
    }

    // --- Helper ---
    private Claims validClaims(String email, String role) {
        Claims claims = mock(Claims.class);
        doReturn(email).when(claims).getSubject();
        doReturn(role).when(claims).get("role", String.class);
        return claims;
    }

    private Claims validClaimsWithJti(String email, String role, String jti) {
        Claims claims = mock(Claims.class);
        doReturn(email).when(claims).getSubject();
        doReturn(role).when(claims).get("role", String.class);
        doReturn(jti).when(claims).getId();
        return claims;
    }

    @Nested
    @DisplayName("Token Blacklist")
    class TokenBlacklistTests {

        @Test
        @DisplayName("Should return 401 when token is blacklisted on protected route")
        void shouldReturn401WhenTokenBlacklisted() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaimsWithJti(TEST_EMAIL, TEST_ROLE, "jti-123");
            when(tokenProvider.validateAndParseClaims(VALID_JWT))
                    .thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(tokenBlacklistService.isBlacklisted("jti-123")).thenReturn(Mono.just(true));

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Should authenticate when token has JTI but is not blacklisted")
        void shouldAuthenticateWhenTokenNotBlacklisted() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaimsWithJti(TEST_EMAIL, TEST_ROLE, "jti-456");
            when(tokenProvider.validateAndParseClaims(VALID_JWT))
                    .thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(tokenBlacklistService.isBlacklisted("jti-456")).thenReturn(Mono.just(false));
            when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Mono.just(activeUser));

            SecurityContext[] captured = new SecurityContext[1];
            WebFilterChain chain = capturingChain(captured);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].getAuthentication().getName()).isEqualTo(TEST_EMAIL);
        }

        @Test
        @DisplayName("Should allow exempt auth path when token is blacklisted")
        void shouldAllowExemptPathWhenTokenBlacklisted() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/admin/auth/login")
                    .header("Authorization", "Bearer " + VALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            Claims claims = validClaimsWithJti(TEST_EMAIL, TEST_ROLE, "jti-789");
            when(tokenProvider.validateAndParseClaims(VALID_JWT))
                    .thenReturn(JwtTokenProvider.TokenValidationResult.success(claims));
            when(tokenBlacklistService.isBlacklisted("jti-789")).thenReturn(Mono.just(true));

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            // Exempt path should NOT return 401
            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("Exempt Path Handling")
    class ExemptPathTests {

        @Test
        @DisplayName("Should allow exempt refresh path when token expired")
        void shouldAllowRefreshPathWhenTokenExpired() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/admin/auth/refresh")
                    .header("Authorization", "Bearer " + INVALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(tokenProvider.validateAndParseClaims(INVALID_JWT))
                    .thenReturn(JwtTokenProvider.TokenValidationResult.expired("Token expired"));

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Should allow public path when token is invalid")
        void shouldAllowPublicPathWhenTokenInvalid() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/public/resume")
                    .header("Authorization", "Bearer " + INVALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(tokenProvider.validateAndParseClaims(INVALID_JWT))
                    .thenReturn(JwtTokenProvider.TokenValidationResult.invalid("Invalid token"));

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            // Public paths should pass through even with invalid token
            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Should allow logout path when token expired")
        void shouldAllowLogoutPathWhenTokenExpired() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/admin/auth/logout")
                    .header("Authorization", "Bearer " + INVALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(tokenProvider.validateAndParseClaims(INVALID_JWT))
                    .thenReturn(JwtTokenProvider.TokenValidationResult.expired("Token expired"));

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Should return 401 for non-exempt path with expired token")
        void shouldReturn401ForNonExemptPathWithExpiredToken() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/admin/articles")
                    .header("Authorization", "Bearer " + INVALID_JWT)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            when(tokenProvider.validateAndParseClaims(INVALID_JWT))
                    .thenReturn(JwtTokenProvider.TokenValidationResult.expired("Token expired"));

            StepVerifier.create(filter.filter(exchange, passThroughChain()))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
