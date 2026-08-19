package dev.catananti.controller;

import dev.catananti.dto.*;
import dev.catananti.entity.User;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.repository.UserRepository;
import dev.catananti.security.AuthCookieService;
import dev.catananti.service.AuthService;
import dev.catananti.service.EmailOtpService;
import dev.catananti.service.MfaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository;
import org.springframework.security.web.reactive.result.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MfaController Tests")
class MfaControllerTest {

    @Mock
    private MfaService mfaService;

    @Mock
    private EmailOtpService emailOtpService;

    @Mock
    private AuthService authService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private MessageSource messageSource;

    @Mock
    private ServerCsrfTokenRepository csrfTokenRepository;

    @Mock
    private BlogMetrics blogMetrics;

    // AUD19C-C1B: real instance so the /verify tests assert the actual cookie contract
    private final AuthCookieService authCookieService = new AuthCookieService(86400000L, true, "");

    private WebTestClient webTestClient;
    private WebTestClient unauthenticatedClient;

    private User testUser() {
        return User.builder()
                .id(1L)
                .email("admin@test.com")
                .name("Admin")
                .passwordHash("hashed")
                .role("ADMIN")
                .build();
    }

    @BeforeEach
    void setUp() {
        MfaController controller = new MfaController(mfaService, emailOtpService, authService,
                userRepository, passwordEncoder, redisTemplate, messageSource,
                authCookieService, csrfTokenRepository, blogMetrics);

        var auth = new UsernamePasswordAuthenticationToken("admin@test.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var secCtx = new SecurityContextImpl(auth);
        WebFilter secFilter = (exchange, chain) -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(secCtx)));

        webTestClient = WebTestClient.bindToController(controller)
                .webFilter(secFilter)
                .argumentResolvers(configurer -> configurer.addCustomResolver(
                        new AuthenticationPrincipalArgumentResolver(ReactiveAdapterRegistry.getSharedInstance())))
                .configureClient().build();

        unauthenticatedClient = WebTestClient.bindToController(controller)
                .argumentResolvers(configurer -> configurer.addCustomResolver(
                        new AuthenticationPrincipalArgumentResolver(ReactiveAdapterRegistry.getSharedInstance())))
                .configureClient().build();
    }

    @SuppressWarnings("unchecked")
    private void stubRateLimitAllowed() {
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.increment(anyString())).thenReturn(Mono.just(1L));
        // AUD18-L7: the controller now checks-and-repairs the TTL on every pass
        lenient().when(redisTemplate.getExpire(anyString())).thenReturn(Mono.just(java.time.Duration.ZERO));
        lenient().when(redisTemplate.expire(anyString(), any())).thenReturn(Mono.just(true));
    }

    @Nested
    @DisplayName("POST /api/v1/admin/mfa/setup")
    class Setup {

        @Test
        @DisplayName("Should initiate TOTP setup for authenticated user")
        void shouldInitiateTotpSetup() {
            stubRateLimitAllowed();
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(testUser()));

            MfaSetupResponse setupResponse = MfaSetupResponse.builder()
                    .qrCodeDataUri("data:image/png;base64,qrcode")
                    .secretKey("JBSWY3DPEHPK3PXP")
                    .method("TOTP")
                    .build();
            when(mfaService.setupTotp(eq(1L), eq("admin@test.com"))).thenReturn(Mono.just(setupResponse));

            webTestClient
                    .post().uri("/api/v1/admin/mfa/setup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("method", "TOTP"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.qrCodeDataUri").isEqualTo("data:image/png;base64,qrcode")
                    .jsonPath("$.secretKey").isEqualTo("JBSWY3DPEHPK3PXP");
        }

        @Test
        @DisplayName("Should initiate EMAIL setup for authenticated user")
        void shouldInitiateEmailSetup() {
            stubRateLimitAllowed();
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(testUser()));
            when(emailOtpService.initSetup(1L)).thenReturn(Mono.empty());
            when(messageSource.getMessage(eq("mfa.otp.sent"), isNull(), eq("mfa.otp.sent"), any(java.util.Locale.class)))
                    .thenReturn("Verification code sent to your email");

            webTestClient
                    .post().uri("/api/v1/admin/mfa/setup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("method", "EMAIL"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.method").isEqualTo("EMAIL")
                    .jsonPath("$.message").isEqualTo("Verification code sent to your email");
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/mfa/verify-setup")
    class VerifySetup {

        @Test
        @DisplayName("Should verify TOTP setup successfully")
        void shouldVerifyTotpSetup() {
            stubRateLimitAllowed();
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(testUser()));
            when(mfaService.verifySetup(eq(1L), eq("123456")))
                    .thenReturn(Mono.just(List.of("BACKUP1", "BACKUP2")));
            when(messageSource.getMessage(eq("mfa.totp.setup.complete"), isNull(), eq("mfa.totp.setup.complete"), any(java.util.Locale.class)))
                    .thenReturn("TOTP setup complete");

            webTestClient
                    .post().uri("/api/v1/admin/mfa/verify-setup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("code", "123456", "method", "TOTP"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.verified").isEqualTo(true)
                    .jsonPath("$.backupCodes").isNotEmpty();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/mfa/verify")
    class VerifyLogin {

        @Test
        @DisplayName("AUD19C-C1B: Should establish the session — auth cookies + CSRF rotation + metric")
        void shouldVerifyMfaLoginCode() {
            TokenResponse tokenResponse = TokenResponse.builder()
                    .accessToken("access-token")
                    .refreshToken("refresh-token")
                    .expiresIn(3600)
                    .email("admin@test.com")
                    .name("Admin")
                    .build();
            when(authService.completeMfaLogin(eq("mfa-token-123"), eq("123456"), eq("TOTP"), any(), any()))
                    .thenReturn(Mono.just(tokenResponse));
            when(csrfTokenRepository.generateToken(any())).thenReturn(Mono.just(mock(CsrfToken.class)));
            when(csrfTokenRepository.saveToken(any(), any())).thenReturn(Mono.empty());

            unauthenticatedClient
                    .post().uri("/api/v1/admin/mfa/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("mfaToken", "mfa-token-123", "code", "123456", "method", "TOTP"))
                    .exchange()
                    .expectStatus().isOk()
                    // AUD19C-C1B: MFA completion IS the login for MFA accounts — it must set
                    // the same HttpOnly cookies AuthController.login sets.
                    .expectCookie().valueEquals("access_token", "access-token")
                    .expectCookie().httpOnly("access_token", true)
                    .expectCookie().secure("access_token", true)
                    .expectCookie().path("access_token", "/api")
                    .expectCookie().sameSite("access_token", "Lax")
                    .expectCookie().maxAge("access_token", java.time.Duration.ofMillis(86400000L))
                    .expectCookie().valueEquals("refresh_token", "refresh-token")
                    .expectCookie().httpOnly("refresh_token", true)
                    .expectCookie().path("refresh_token", "/api/v1/admin/auth")
                    // behavior note: rememberMe is fixed to false through the MFA challenge -> 24h
                    .expectCookie().maxAge("refresh_token", java.time.Duration.ofHours(24))
                    .expectBody()
                    // SEG-1: accessToken/refreshToken are WRITE_ONLY and must NOT appear in the
                    // JSON body anymore. Assert MFA login still succeeds via the still-present
                    // body fields, and that the tokens are absent.
                    .jsonPath("$.email").isEqualTo("admin@test.com")
                    .jsonPath("$.name").isEqualTo("Admin")
                    .jsonPath("$.tokenType").isEqualTo("Bearer")
                    .jsonPath("$.expiresIn").isEqualTo(3600)
                    .jsonPath("$.accessToken").doesNotExist()
                    .jsonPath("$.refreshToken").doesNotExist();

            // Q4.2: CSRF rotated after auth state change; Q12.3: the completed MFA
            // verification is the single login-success datapoint.
            verify(csrfTokenRepository).generateToken(any());
            verify(blogMetrics).incrementLoginSuccess();
        }

        @Test
        @DisplayName("AUD19C-C1B: Should set no cookies and no metric when the code is wrong")
        void shouldNotEstablishSession_WhenCodeInvalid() {
            when(authService.completeMfaLogin(eq("mfa-token-123"), eq("999999"), eq("TOTP"), any(), any()))
                    .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.UNAUTHORIZED, "error.mfa_code_invalid")));

            unauthenticatedClient
                    .post().uri("/api/v1/admin/mfa/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("mfaToken", "mfa-token-123", "code", "999999", "method", "TOTP"))
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectCookie().doesNotExist("access_token")
                    .expectCookie().doesNotExist("refresh_token");

            verify(blogMetrics, never()).incrementLoginSuccess();
            verify(csrfTokenRepository, never()).generateToken(any());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/mfa/status")
    class Status {

        @Test
        @DisplayName("Should return MFA status for authenticated user")
        void shouldReturnMfaStatus() {
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(testUser()));
            MfaStatusResponse statusResponse = MfaStatusResponse.builder()
                    .mfaEnabled(true)
                    .methods(List.of("TOTP"))
                    .preferredMethod("TOTP")
                    .backupCodesRemaining(8)
                    .build();
            when(mfaService.getStatus(1L)).thenReturn(Mono.just(statusResponse));

            webTestClient
                    .get().uri("/api/v1/admin/mfa/status")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.mfaEnabled").isEqualTo(true)
                    .jsonPath("$.methods[0]").isEqualTo("TOTP")
                    .jsonPath("$.backupCodesRemaining").isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/mfa/disable")
    class Disable {

        @Test
        @DisplayName("Should disable MFA with correct password")
        void shouldDisableMfaWithCorrectPassword() {
            User user = testUser();
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(user));
            when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
            when(mfaService.disableMfa(1L)).thenReturn(Mono.empty());

            webTestClient
                    .method(HttpMethod.DELETE).uri("/api/v1/admin/mfa/disable")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("password", "password123"))
                    .exchange()
                    .expectStatus().isNoContent();
        }

        @Test
        @DisplayName("AUD19C-A5: Should return 400 (not 401) on wrong re-auth password")
        void shouldReturn400_WhenPasswordWrong() {
            User user = testUser();
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(user));
            when(passwordEncoder.matches("wrong-password", "hashed")).thenReturn(false);

            webTestClient
                    .method(HttpMethod.DELETE).uri("/api/v1/admin/mfa/disable")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("password", "wrong-password"))
                    .exchange()
                    // business validation failure, NOT a broken session — 401 here made
                    // API clients drop a perfectly valid session
                    .expectStatus().isBadRequest();

            verify(mfaService, never()).disableMfa(anyLong());
        }

        @Test
        @DisplayName("Should return 400 when the password field is missing from the body")
        void shouldReturn400_WhenPasswordMissing() {
            webTestClient
                    .method(HttpMethod.DELETE).uri("/api/v1/admin/mfa/disable")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of())
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(userRepository, never()).findByEmail(anyString());
        }
    }
}
