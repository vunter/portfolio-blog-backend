package dev.catananti.controller;

import dev.catananti.dto.LoginRequest;
import dev.catananti.dto.TokenResponse;
import dev.catananti.entity.RefreshToken;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.repository.UserRepository;
import dev.catananti.security.AuthCookieService;
import dev.catananti.service.AuthService;
import dev.catananti.service.RecaptchaService;
import dev.catananti.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthController using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private RecaptchaService recaptchaService;

    @Mock
    private dev.catananti.service.EmailChangeService emailChangeService;

    @Mock
    private dev.catananti.service.EmailVerificationService emailVerificationService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ServerCsrfTokenRepository csrfTokenRepository;

    @Mock
    private BlogMetrics blogMetrics;

    // AUD19C-C1B: real instance so the tests exercise the actual cookie attributes
    // (jwtExpirationMs=86400000, secure=true, no domain) instead of stubbing them.
    @Spy
    private AuthCookieService authCookieService = new AuthCookieService(86400000L, true, "");

    @Mock
    private ServerHttpRequest mockRequest;

    @Mock
    private ServerHttpResponse mockResponse;

    @Mock
    private org.springframework.web.server.ServerWebExchange mockExchange;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        lenient().when(recaptchaService.verify(any(), any())).thenReturn(Mono.empty());
        HttpHeaders headers = new HttpHeaders();
        lenient().when(mockRequest.getHeaders()).thenReturn(headers);
        lenient().when(mockRequest.getRemoteAddress())
                .thenReturn(new InetSocketAddress("127.0.0.1", 8080));

        MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
        lenient().when(mockRequest.getCookies()).thenReturn(cookies);

        // Mock CSRF token rotation used in login/logout
        CsrfToken mockCsrfToken = mock(CsrfToken.class);
        lenient().when(csrfTokenRepository.generateToken(any())).thenReturn(Mono.just(mockCsrfToken));
        lenient().when(csrfTokenRepository.saveToken(any(), any())).thenReturn(Mono.empty());
    }

    @Nested
    @DisplayName("POST /api/v1/admin/auth/login")
    class Login {

        @Test
        @DisplayName("Should return token response on valid login")
        void shouldReturnAuthResponse_WhenLoginValid() {
            // Given
            LoginRequest loginRequest = new LoginRequest("admin@test.com", "password123", false, null);

            TokenResponse response = TokenResponse.builder()
                    .accessToken("jwt-token")
                    .refreshToken("refresh-token")
                    .tokenType("Bearer")
                    .email("admin@test.com")
                    .name("Admin")
                    .expiresIn(3600)
                    .build();

            when(authService.loginWithRefreshToken(any(LoginRequest.class), anyString(), any()))
                    .thenReturn(Mono.just(response));

            // When & Then
            StepVerifier.create(authController.login(loginRequest, mockRequest, mockResponse, mockExchange))
                    .assertNext(tokenResponse -> {
                        assertThat(tokenResponse.getAccessToken()).isEqualTo("jwt-token");
                        assertThat(tokenResponse.getTokenType()).isEqualTo("Bearer");
                        assertThat(tokenResponse.getEmail()).isEqualTo("admin@test.com");
                        assertThat(tokenResponse.getName()).isEqualTo("Admin");
                    })
                    .verifyComplete();

            verify(authService).loginWithRefreshToken(any(LoginRequest.class), eq("127.0.0.1"), any());
        }

        @Test
        @DisplayName("Should set auth cookies and count success on normal login")
        void shouldSetAuthCookies_OnNormalLogin() {
            LoginRequest loginRequest = new LoginRequest("admin@test.com", "password123!", false, null);
            TokenResponse response = TokenResponse.builder()
                    .accessToken("jwt-token")
                    .refreshToken("refresh-token")
                    .tokenType("Bearer")
                    .expiresIn(3600)
                    .build();
            when(authService.loginWithRefreshToken(any(LoginRequest.class), anyString(), any()))
                    .thenReturn(Mono.just(response));

            StepVerifier.create(authController.login(loginRequest, mockRequest, mockResponse, mockExchange))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<ResponseCookie> cookieCaptor = ArgumentCaptor.forClass(ResponseCookie.class);
            verify(mockResponse, times(2)).addCookie(cookieCaptor.capture());
            ResponseCookie access = cookieCaptor.getAllValues().get(0);
            assertThat(access.getName()).isEqualTo("access_token");
            assertThat(access.getValue()).isEqualTo("jwt-token");
            assertThat(access.isHttpOnly()).isTrue();
            assertThat(access.isSecure()).isTrue();
            assertThat(access.getPath()).isEqualTo("/api");
            assertThat(access.getSameSite()).isEqualTo("Lax");
            assertThat(access.getMaxAge()).isEqualTo(Duration.ofMillis(86400000L));
            ResponseCookie refresh = cookieCaptor.getAllValues().get(1);
            assertThat(refresh.getName()).isEqualTo("refresh_token");
            assertThat(refresh.getValue()).isEqualTo("refresh-token");
            assertThat(refresh.getPath()).isEqualTo("/api/v1/admin/auth");
            // rememberMe=false -> 24h persistent cookie (BUG-03)
            assertThat(refresh.getMaxAge()).isEqualTo(Duration.ofHours(24));

            verify(blogMetrics).incrementLoginSuccess();
            verify(csrfTokenRepository).generateToken(mockExchange);
        }

        @Test
        @DisplayName("AUD19C-C1C: MFA challenge sets NO cookies, no success metric, but still rotates CSRF")
        void shouldNotSetCookiesOrMetric_WhenMfaRequired() {
            LoginRequest loginRequest = new LoginRequest("admin@test.com", "password123!", false, null);
            TokenResponse challenge = TokenResponse.builder()
                    .mfaRequired(true)
                    .mfaToken("11111111-2222-4333-8444-555555555555")
                    .mfaMethod("TOTP")
                    .email("admin@test.com")
                    .build();
            when(authService.loginWithRefreshToken(any(LoginRequest.class), anyString(), any()))
                    .thenReturn(Mono.just(challenge));

            StepVerifier.create(authController.login(loginRequest, mockRequest, mockResponse, mockExchange))
                    .assertNext(resp -> {
                        assertThat(resp.getMfaRequired()).isTrue();
                        assertThat(resp.getMfaToken()).isEqualTo("11111111-2222-4333-8444-555555555555");
                        assertThat(resp.getMfaMethod()).isEqualTo("TOTP");
                        assertThat(resp.getAccessToken()).isNull();
                    })
                    .verifyComplete();

            // A challenge is NOT a session: no auth cookies, no login-success metric
            verify(mockResponse, never()).addCookie(any());
            verify(blogMetrics, never()).incrementLoginSuccess();
            // But the auth state changed (anonymous -> challenged): CSRF still rotates
            verify(csrfTokenRepository).generateToken(mockExchange);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/auth/logout")
    class Logout {

        @Test
        @DisplayName("Should complete on logout with no refresh token cookie")
        void shouldComplete_OnLogoutWithNoRefreshToken() {
            // Given - no cookies set (setUp leaves empty cookie map)

            // When & Then
            StepVerifier.create(authController.logout(mockRequest, mockResponse, mockExchange))
                    .verifyComplete();

            verify(authService, never()).logout(anyString(), anyString());
        }

        @Test
        @DisplayName("Should call authService logout when refresh token cookie present")
        void shouldCallLogout_WhenRefreshTokenInCookie() {
            // Given - add refresh_token cookie to mockRequest
            MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
            cookies.add("refresh_token", new HttpCookie("refresh_token", "refresh-token-123"));
            when(mockRequest.getCookies()).thenReturn(cookies);

            when(authService.logout(eq("refresh-token-123"), any())).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(authController.logout(mockRequest, mockResponse, mockExchange))
                    .verifyComplete();

            verify(authService).logout(eq("refresh-token-123"), any());
        }

        @Test
        @DisplayName("Should revoke all refresh_token cookies on logout")
        void shouldRevokeAllRefreshTokens() {
            MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
            cookies.add("refresh_token", new HttpCookie("refresh_token", "refresh-1"));
            cookies.add("refresh_token", new HttpCookie("refresh_token", "refresh-2"));
            when(mockRequest.getCookies()).thenReturn(cookies);

            when(authService.logout(eq("refresh-1"), any())).thenReturn(Mono.empty());
            when(refreshTokenService.revokeToken("refresh-2")).thenReturn(Mono.empty());

            StepVerifier.create(authController.logout(mockRequest, mockResponse, mockExchange))
                    .verifyComplete();

            verify(authService).logout(eq("refresh-1"), any());
            verify(refreshTokenService).revokeToken("refresh-2");
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/auth/verify")
    class Verify {

        @Test
        @DisplayName("AUD18-H1: Should return valid user info from the String-principal Authentication")
        void shouldReturnUserInfo_WhenAuthenticated() {
            // Given — JwtAuthenticationFilter builds exactly this shape: String principal + role authority
            var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    "admin@test.com", null,
                    java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

            // When & Then
            StepVerifier.create(authController.verifyToken(authentication))
                    .assertNext(result -> {
                        assertThat(result.get("valid")).isEqualTo(true);
                        assertThat(result.get("username")).isEqualTo("admin@test.com");
                        assertThat(result.get("roles")).isEqualTo(java.util.List.of("ROLE_ADMIN"));
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return invalid when no authentication")
        void shouldReturnInvalid_WhenNoAuthentication() {
            // When & Then
            StepVerifier.create(authController.verifyToken(null))
                    .assertNext(result -> assertThat(result.get("valid")).isEqualTo(false))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/auth/refresh")
    class Refresh {

        @Test
        @DisplayName("Should return error when no refresh token cookie")
        void shouldReturnError_WhenNoRefreshToken() {
            // Given - no cookies (setUp leaves empty cookie map)

            // When & Then
            StepVerifier.create(authController.refreshToken(mockRequest, mockResponse))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should refresh token from cookie")
        void shouldRefreshToken_FromCookie() {
            // Given - add refresh_token cookie to mockRequest
            MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
            cookies.add("refresh_token", new HttpCookie("refresh_token", "old-refresh-token"));
            when(mockRequest.getCookies()).thenReturn(cookies);

            TokenResponse response = TokenResponse.builder()
                    .accessToken("new-access-token")
                    .refreshToken("new-refresh-token")
                    .tokenType("Bearer")
                    .build();

            when(authService.refreshAccessToken(eq("old-refresh-token"), anyString(), any()))
                    .thenReturn(Mono.just(response));

            // When & Then
            StepVerifier.create(authController.refreshToken(mockRequest, mockResponse))
                    .assertNext(tokenResponse -> {
                        assertThat(tokenResponse.getAccessToken()).isEqualTo("new-access-token");
                        assertThat(tokenResponse.getRefreshToken()).isEqualTo("new-refresh-token");
                        assertThat(tokenResponse.getTokenType()).isEqualTo("Bearer");
                    })
                    .verifyComplete();

            verify(authService).refreshAccessToken(eq("old-refresh-token"), anyString(), any());
        }

        @Test
        @DisplayName("AUD18-L4: Should rethrow the first error without re-invoking refresh when all cookies fail")
        void shouldRethrowFirstError_WithoutSecondRefreshAttempt() {
            MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
            cookies.add("refresh_token", new HttpCookie("refresh_token", "revoked-token"));
            when(mockRequest.getCookies()).thenReturn(cookies);

            SecurityException theftError = new SecurityException("error.token_theft_detected");
            when(authService.refreshAccessToken(eq("revoked-token"), anyString(), any()))
                    .thenReturn(Mono.error(theftError));

            StepVerifier.create(authController.refreshToken(mockRequest, mockResponse))
                    .expectErrorMatches(e -> e == theftError)
                    .verify();

            // The old fallback re-ran the refresh to reproduce the error, running theft
            // detection twice — the token must be tried exactly once now.
            verify(authService, times(1)).refreshAccessToken(eq("revoked-token"), anyString(), any());
        }

        @Test
        @DisplayName("AUD18-L4: Should fall through to the next cookie and succeed after a failure")
        void shouldFallThroughToNextCookie_WhenFirstFails() {
            MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
            cookies.add("refresh_token", new HttpCookie("refresh_token", "working-token"));
            cookies.add("refresh_token", new HttpCookie("refresh_token", "revoked-token"));
            when(mockRequest.getCookies()).thenReturn(cookies);

            TokenResponse response = TokenResponse.builder()
                    .accessToken("new-access-token")
                    .refreshToken("new-refresh-token")
                    .tokenType("Bearer")
                    .build();

            // Cookies are tried in reverse order (most recent first): revoked-token fails,
            // then the fallback succeeds with working-token
            when(authService.refreshAccessToken(eq("revoked-token"), anyString(), any()))
                    .thenReturn(Mono.error(new SecurityException("error.invalid_refresh_token")));
            when(authService.refreshAccessToken(eq("working-token"), anyString(), any()))
                    .thenReturn(Mono.just(response));

            StepVerifier.create(authController.refreshToken(mockRequest, mockResponse))
                    .assertNext(tokenResponse ->
                            assertThat(tokenResponse.getAccessToken()).isEqualTo("new-access-token"))
                    .verifyComplete();

            // Each token is refreshed at most once — no error-reproduction re-invocation
            verify(authService, times(1)).refreshAccessToken(eq("revoked-token"), anyString(), any());
            verify(authService, times(1)).refreshAccessToken(eq("working-token"), anyString(), any());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/auth/verify-email-change")
    class VerifyEmailChange {

        @Test
        @DisplayName("Should verify email change successfully")
        void shouldVerifyEmailChange() {
            when(emailChangeService.verifyEmailChange("test-token"))
                    .thenReturn(Mono.just("new@example.com"));

            StepVerifier.create(authController.verifyEmailChange("test-token"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).containsEntry("email", "new@example.com");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return bad request on invalid token")
        void shouldReturnBadRequestOnInvalidToken() {
            when(emailChangeService.verifyEmailChange("bad-token"))
                    .thenReturn(Mono.error(new RuntimeException("Invalid token")));

            StepVerifier.create(authController.verifyEmailChange("bad-token"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(400);
                        assertThat(response.getBody()).containsKey("message");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/auth/revert-email-change")
    class RevertEmailChange {

        @Test
        @DisplayName("Should revert email change successfully")
        void shouldRevertEmailChange() {
            when(emailChangeService.revertEmailChange("revert-token"))
                    .thenReturn(Mono.just("original@example.com"));

            StepVerifier.create(authController.revertEmailChange("revert-token"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).containsEntry("email", "original@example.com");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return bad request on invalid revert token")
        void shouldReturnBadRequestOnInvalidRevertToken() {
            when(emailChangeService.revertEmailChange("bad-token"))
                    .thenReturn(Mono.error(new RuntimeException("Invalid token")));

            StepVerifier.create(authController.revertEmailChange("bad-token"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(400);
                        assertThat(response.getBody()).containsKey("message");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/auth/verify-email")
    class VerifyEmail {

        @Test
        @DisplayName("Should return 200 on valid token")
        void verifyEmailReturnsOkOnValidToken() {
            when(emailVerificationService.verify("tok")).thenReturn(Mono.just(10L));

            StepVerifier.create(authController.verifyEmail("tok"))
                    .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(200))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/auth/resend-verification")
    class ResendVerification {

        @Test
        @DisplayName("Should return 202 for the authenticated user")
        void resendVerificationReturnsAccepted() {
            when(emailVerificationService.sendVerification("user@test.dev")).thenReturn(Mono.empty());

            StepVerifier.create(authController.resendVerification("user@test.dev"))
                    .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(202))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET/DELETE /api/v1/admin/auth/sessions")
    class Sessions {

        @Test
        @DisplayName("AUD19C-SESSID: Should serialize session ids as strings (Snowflakes > 2^53)")
        void shouldReturnSessionIdsAsStrings() {
            long snowflakeId = 1234567890123456789L; // would lose precision as a JSON number
            RefreshToken session = RefreshToken.builder()
                    .id(snowflakeId)
                    .userId(1L)
                    .token("tok")
                    .deviceName("Firefox on Linux")
                    .ipAddress("203.0.113.7")
                    .createdAt(java.time.LocalDateTime.of(2026, 8, 1, 10, 0))
                    .lastUsedAt(java.time.LocalDateTime.of(2026, 8, 18, 9, 30))
                    .expiresAt(java.time.LocalDateTime.of(2026, 8, 25, 10, 0))
                    .build();
            when(authService.resolveUserIdByEmail("admin@test.com")).thenReturn(Mono.just(1L));
            when(refreshTokenService.getActiveSessions(1L)).thenReturn(Flux.just(session));

            StepVerifier.create(authController.getActiveSessions("admin@test.com"))
                    .assertNext(resp -> {
                        assertThat(resp.getStatusCode().value()).isEqualTo(200);
                        List<Map<String, Object>> body = resp.getBody();
                        assertThat(body).hasSize(1);
                        assertThat(body.get(0).get("id"))
                                .isInstanceOf(String.class)
                                .isEqualTo("1234567890123456789");
                        assertThat(body.get(0).get("deviceName")).isEqualTo("Firefox on Linux");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should revoke a session by id for the authenticated user")
        void shouldRevokeSessionById() {
            when(authService.resolveUserIdByEmail("admin@test.com")).thenReturn(Mono.just(1L));
            when(refreshTokenService.revokeTokenById(1234567890123456789L, 1L)).thenReturn(Mono.empty());

            StepVerifier.create(authController.revokeSession(1234567890123456789L, "admin@test.com"))
                    .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(204))
                    .verifyComplete();

            verify(refreshTokenService).revokeTokenById(1234567890123456789L, 1L);
        }
    }
}
