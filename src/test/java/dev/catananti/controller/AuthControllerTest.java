package dev.catananti.controller;

import dev.catananti.dto.AuthResponse;
import dev.catananti.dto.LoginRequest;
import dev.catananti.dto.TokenResponse;
import dev.catananti.service.AuthService;
import dev.catananti.service.RecaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
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
    private ServerHttpRequest mockRequest;

    @Mock
    private ServerHttpResponse mockResponse;

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
    }

    @Nested
    @DisplayName("POST /api/v1/admin/auth/login")
    class Login {

        @Test
        @DisplayName("Should return auth response on valid login")
        void shouldReturnAuthResponse_WhenLoginValid() {
            // Given
            LoginRequest loginRequest = new LoginRequest("admin@test.com", "password123", false, null);

            AuthResponse response = AuthResponse.builder()
                    .token("jwt-token")
                    .type("Bearer")
                    .email("admin@test.com")
                    .name("Admin")
                    .build();

            when(authService.login(any(LoginRequest.class), anyString()))
                    .thenReturn(Mono.just(response));

            // When & Then
            StepVerifier.create(authController.login(loginRequest, mockRequest, mockResponse))
                    .assertNext(authResponse -> {
                        assertThat(authResponse.getToken()).isEqualTo("jwt-token");
                        assertThat(authResponse.getType()).isEqualTo("Bearer");
                        assertThat(authResponse.getEmail()).isEqualTo("admin@test.com");
                        assertThat(authResponse.getName()).isEqualTo("Admin");
                    })
                    .verifyComplete();

            verify(authService).login(any(LoginRequest.class), eq("127.0.0.1"));
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
            StepVerifier.create(authController.logout(mockRequest, mockResponse))
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
            StepVerifier.create(authController.logout(mockRequest, mockResponse))
                    .verifyComplete();

            verify(authService).logout(eq("refresh-token-123"), any());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/auth/verify")
    class Verify {

        @Test
        @DisplayName("Should return valid user info when authenticated")
        @SuppressWarnings("unchecked")
        void shouldReturnUserInfo_WhenAuthenticated() {
            // Given
            UserDetails mockUserDetails = mock(UserDetails.class);
            when(mockUserDetails.getUsername()).thenReturn("admin@test.com");
            when(mockUserDetails.getAuthorities()).thenReturn(
                    (java.util.Collection) java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );

            // When & Then
            StepVerifier.create(authController.verifyToken(mockUserDetails))
                    .assertNext(result -> {
                        assertThat(result.get("valid")).isEqualTo(true);
                        assertThat(result.get("username")).isEqualTo("admin@test.com");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return invalid when no user details")
        void shouldReturnInvalid_WhenNoUserDetails() {
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

            when(authService.refreshAccessToken("old-refresh-token"))
                    .thenReturn(Mono.just(response));

            // When & Then
            StepVerifier.create(authController.refreshToken(mockRequest, mockResponse))
                    .assertNext(tokenResponse -> {
                        assertThat(tokenResponse.getAccessToken()).isEqualTo("new-access-token");
                        assertThat(tokenResponse.getRefreshToken()).isEqualTo("new-refresh-token");
                        assertThat(tokenResponse.getTokenType()).isEqualTo("Bearer");
                    })
                    .verifyComplete();

            verify(authService).refreshAccessToken("old-refresh-token");
        }
    }
}
