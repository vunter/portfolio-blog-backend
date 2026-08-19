package dev.catananti.controller;

import dev.catananti.dto.TokenResponse;
import dev.catananti.entity.User;
import dev.catananti.entity.UserSocialAccount;
import dev.catananti.repository.UserRepository;
import dev.catananti.repository.UserSocialAccountRepository;
import dev.catananti.security.AuthCookieService;
import dev.catananti.service.OAuth2Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.reactive.result.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2Controller Tests")
class OAuth2ControllerTest {

    @Mock
    private OAuth2Service oAuth2Service;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSocialAccountRepository socialAccountRepository;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        // AUD19C-C1B: real cookie service — cookie attribute assertions below exercise
        // the shared contract the controller migrated to.
        AuthCookieService authCookieService = new AuthCookieService(86400000L, true, "");
        OAuth2Controller controller = new OAuth2Controller(oAuth2Service, userRepository,
                socialAccountRepository, authCookieService);

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
    }

    @Nested
    @DisplayName("GET /api/v1/admin/auth/oauth2/providers")
    class GetProviders {

        @Test
        @DisplayName("Should return available OAuth2 providers")
        void shouldReturnAvailableProviders() {
            when(oAuth2Service.isGoogleEnabled()).thenReturn(true);
            when(oAuth2Service.isGithubEnabled()).thenReturn(true);
            when(oAuth2Service.isLinkedinEnabled()).thenReturn(false);

            webTestClient
                    .get().uri("/api/v1/admin/auth/oauth2/providers")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.google").isEqualTo(true)
                    .jsonPath("$.github").isEqualTo(true)
                    .jsonPath("$.linkedin").isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/auth/oauth2/authorize/{provider}")
    class Authorize {

        @Test
        @DisplayName("Should redirect to Google auth URL")
        void shouldRedirectToGoogleAuthUrl() {
            when(oAuth2Service.getGoogleAuthUrl(anyString()))
                    .thenReturn("https://accounts.google.com/o/oauth2/auth?client_id=test");
            when(oAuth2Service.storeState(anyString())).thenReturn(Mono.empty());

            webTestClient
                    .get().uri("/api/v1/admin/auth/oauth2/authorize/google")
                    .exchange()
                    .expectStatus().isFound()
                    .expectHeader().valueMatches("Location", "https://accounts\\.google\\.com/.*");
        }

        @Test
        @DisplayName("Should return 400 for unsupported provider")
        void shouldReturn400ForUnsupportedProvider() {
            webTestClient
                    .get().uri("/api/v1/admin/auth/oauth2/authorize/unsupported")
                    .exchange()
                    .expectStatus().isBadRequest();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/auth/oauth2/callback/{provider}")
    class Callback {

        @Test
        @DisplayName("Should handle Google callback and redirect")
        void shouldHandleGoogleCallback() {
            String validState = "a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5";
            when(oAuth2Service.validateAndConsumeState(validState)).thenReturn(Mono.just(true));
            TokenResponse tokenResponse = TokenResponse.builder()
                    .accessToken("access-token-123")
                    .refreshToken("refresh-token-123")
                    .expiresIn(3600)
                    .email("admin@test.com")
                    .name("Admin")
                    .build();
            when(oAuth2Service.handleGoogleCallback(eq("code123"), anyString()))
                    .thenReturn(Mono.just(tokenResponse));

            webTestClient
                    .get().uri("/api/v1/admin/auth/oauth2/callback/google?code=code123&state=" + validState)
                    .cookie("oauth_state", validState)
                    .exchange()
                    .expectStatus().isFound()
                    .expectHeader().valueMatches("Location", ".*/auth/oauth-callback.*")
                    // AUD19C-C1B: post-migration to AuthCookieService the cookie behavior is
                    // unchanged: HttpOnly+Secure+Lax, access on /api, refresh on the auth path
                    // with its previous 7-day lifetime (rememberMe=true semantics).
                    .expectCookie().valueEquals("access_token", "access-token-123")
                    .expectCookie().httpOnly("access_token", true)
                    .expectCookie().secure("access_token", true)
                    .expectCookie().sameSite("access_token", "Lax")
                    .expectCookie().path("access_token", "/api")
                    .expectCookie().valueEquals("refresh_token", "refresh-token-123")
                    .expectCookie().httpOnly("refresh_token", true)
                    .expectCookie().path("refresh_token", "/api/v1/admin/auth")
                    .expectCookie().maxAge("refresh_token", java.time.Duration.ofDays(7));
        }

        @Test
        @DisplayName("AUD18-A10: Should redirect to the MFA challenge without setting auth cookies")
        void shouldRedirectToMfaChallenge_WhenMfaRequired() {
            String validState = "a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5";
            when(oAuth2Service.validateAndConsumeState(validState)).thenReturn(Mono.just(true));
            TokenResponse mfaChallenge = TokenResponse.builder()
                    .mfaRequired(true)
                    .mfaToken("11111111-2222-4333-8444-555555555555")
                    .email("admin@test.com")
                    .name("Admin")
                    .build();
            when(oAuth2Service.handleGoogleCallback(eq("code123"), anyString()))
                    .thenReturn(Mono.just(mfaChallenge));

            webTestClient
                    .get().uri("/api/v1/admin/auth/oauth2/callback/google?code=code123&state=" + validState)
                    .cookie("oauth_state", validState)
                    .exchange()
                    .expectStatus().isFound()
                    // Challenge travels in the URL fragment, never as cookies
                    .expectHeader().valueMatches("Location",
                            ".*/auth/oauth-callback#mfa_required=true&mfa_token=11111111-2222-4333-8444-555555555555.*")
                    .expectHeader().value("Set-Cookie", value ->
                            org.assertj.core.api.Assertions.assertThat(value).doesNotContain("access_token"));
        }

        @Test
        @DisplayName("Should return error for invalid state")
        void shouldReturnErrorForInvalidState() {
            webTestClient
                    .get().uri("/api/v1/admin/auth/oauth2/callback/google?code=code123&state=bad-state")
                    .exchange()
                    .expectStatus().isBadRequest();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/auth/oauth2/accounts")
    class GetLinkedAccounts {

        @Test
        @DisplayName("Should return linked social accounts for authenticated user")
        void shouldReturnLinkedAccounts() {
            User user = User.builder().id(1L).email("admin@test.com").name("Admin").build();
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(user));

            UserSocialAccount account = UserSocialAccount.builder()
                    .id(10L)
                    .provider("google")
                    .providerEmail("admin@gmail.com")
                    .displayName("Admin Google")
                    .linkedAt(LocalDateTime.of(2025, 1, 15, 10, 0))
                    .build();

            when(socialAccountRepository.findByUserId(1L)).thenReturn(Flux.just(account));

            webTestClient
                    .get().uri("/api/v1/admin/auth/oauth2/accounts")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    // AUD19C-SESSID: Snowflake ids serialize as strings
                    .jsonPath("$[0].id").isEqualTo("10")
                    .jsonPath("$[0].provider").isEqualTo("google")
                    .jsonPath("$[0].providerEmail").isEqualTo("admin@gmail.com");
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/auth/oauth2/accounts/{provider}")
    class UnlinkAccount {

        @Test
        @DisplayName("Should unlink a social account")
        void shouldUnlinkAccount() {
            User user = User.builder().id(1L).email("admin@test.com").name("Admin").build();
            when(userRepository.findByEmail("admin@test.com")).thenReturn(Mono.just(user));
            when(oAuth2Service.unlinkAccount(1L, "google")).thenReturn(Mono.empty());

            webTestClient
                    .delete().uri("/api/v1/admin/auth/oauth2/accounts/google")
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }
}
