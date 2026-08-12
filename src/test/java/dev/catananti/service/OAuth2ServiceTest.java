package dev.catananti.service;

import dev.catananti.config.ResilienceConfig;
import dev.catananti.dto.TokenResponse;
import dev.catananti.entity.RefreshToken;
import dev.catananti.entity.User;
import dev.catananti.entity.UserSocialAccount;
import dev.catananti.repository.UserRepository;
import dev.catananti.repository.UserSocialAccountRepository;
import dev.catananti.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuth2ServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSocialAccountRepository socialAccountRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private IdService idService;

    @Mock
    private AuditService auditService;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @Mock
    private ResilienceConfig resilienceConfig;

    private OAuth2Service oAuth2Service;

    private User testUser;
    private RefreshToken testRefreshToken;

    @BeforeEach
    void setUp() {
        oAuth2Service = new OAuth2Service(
                userRepository, socialAccountRepository, refreshTokenService,
                tokenProvider, idService, auditService, redisTemplate, resilienceConfig,
                new tools.jackson.databind.ObjectMapper(),
                org.springframework.web.reactive.function.client.WebClient.builder());

        // Set @Value fields via reflection
        setField("googleClientId", "google-client-id");
        setField("googleClientSecret", "google-client-secret");
        setField("githubClientId", "github-client-id");
        setField("githubClientSecret", "github-client-secret");
        setField("linkedinClientId", "linkedin-client-id");
        setField("linkedinClientSecret", "linkedin-client-secret");
        setField("redirectBaseUrl", "https://example.com");
        setField("jwtExpirationMs", 900000L);

        testUser = User.builder()
                .id(1234567890123456789L)
                .email("test@example.com")
                .passwordHash("$2a$10$hashedpassword")
                .name("Test User")
                .role("ADMIN")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testRefreshToken = RefreshToken.builder()
                .id(1L)
                .userId(testUser.getId())
                .token("refresh-token-value")
                .build();
    }

    private void setField(String fieldName, Object value) {
        try {
            var field = OAuth2Service.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(oAuth2Service, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    // ==================== Provider Enabled Checks ====================

    @Nested
    @DisplayName("Provider enabled checks")
    class ProviderEnabledChecks {

        @Test
        @DisplayName("Should return true when Google client ID is configured")
        void isGoogleEnabled_ShouldReturnTrue_WhenClientIdSet() {
            assertThat(oAuth2Service.isGoogleEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should return false when Google client ID is empty")
        void isGoogleEnabled_ShouldReturnFalse_WhenClientIdEmpty() {
            setField("googleClientId", "");
            assertThat(oAuth2Service.isGoogleEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should return false when Google client ID is null")
        void isGoogleEnabled_ShouldReturnFalse_WhenClientIdNull() {
            setField("googleClientId", null);
            assertThat(oAuth2Service.isGoogleEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should return false when Google client ID is blank")
        void isGoogleEnabled_ShouldReturnFalse_WhenClientIdBlank() {
            setField("googleClientId", "   ");
            assertThat(oAuth2Service.isGoogleEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should return true when GitHub client ID is configured")
        void isGithubEnabled_ShouldReturnTrue_WhenClientIdSet() {
            assertThat(oAuth2Service.isGithubEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should return false when GitHub client ID is empty")
        void isGithubEnabled_ShouldReturnFalse_WhenClientIdEmpty() {
            setField("githubClientId", "");
            assertThat(oAuth2Service.isGithubEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should return false when GitHub client ID is null")
        void isGithubEnabled_ShouldReturnFalse_WhenClientIdNull() {
            setField("githubClientId", null);
            assertThat(oAuth2Service.isGithubEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should return true when LinkedIn client ID is configured")
        void isLinkedinEnabled_ShouldReturnTrue_WhenClientIdSet() {
            assertThat(oAuth2Service.isLinkedinEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should return false when LinkedIn client ID is empty")
        void isLinkedinEnabled_ShouldReturnFalse_WhenClientIdEmpty() {
            setField("linkedinClientId", "");
            assertThat(oAuth2Service.isLinkedinEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should return false when LinkedIn client ID is null")
        void isLinkedinEnabled_ShouldReturnFalse_WhenClientIdNull() {
            setField("linkedinClientId", null);
            assertThat(oAuth2Service.isLinkedinEnabled()).isFalse();
        }
    }

    // ==================== Auth URL Generation ====================

    @Nested
    @DisplayName("Auth URL generation")
    class AuthUrlGeneration {

        @Test
        @DisplayName("Should generate Google auth URL with encoded state and redirect")
        void getGoogleAuthUrl_ShouldContainRequiredParams() {
            String url = oAuth2Service.getGoogleAuthUrl("test-state-123");

            assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
            assertThat(url).contains("client_id=google-client-id");
            assertThat(url).contains("response_type=code");
            assertThat(url).contains("scope=openid%20email%20profile");
            assertThat(url).contains("state=test-state-123");
            assertThat(url).contains("access_type=offline");
            assertThat(url).contains("redirect_uri=");
        }

        @Test
        @DisplayName("Should URL-encode special characters in Google state parameter")
        void getGoogleAuthUrl_ShouldEncodeSpecialChars() {
            String url = oAuth2Service.getGoogleAuthUrl("state with spaces&special=chars");

            assertThat(url).contains("state=state+with+spaces%26special%3Dchars");
        }

        @Test
        @DisplayName("Should generate GitHub auth URL with encoded state and redirect")
        void getGithubAuthUrl_ShouldContainRequiredParams() {
            String url = oAuth2Service.getGithubAuthUrl("gh-state-456");

            assertThat(url).startsWith("https://github.com/login/oauth/authorize");
            assertThat(url).contains("client_id=github-client-id");
            assertThat(url).contains("scope=user:email");
            assertThat(url).contains("state=gh-state-456");
            assertThat(url).contains("redirect_uri=");
        }

        @Test
        @DisplayName("Should generate LinkedIn auth URL with encoded state and redirect")
        void getLinkedinAuthUrl_ShouldContainRequiredParams() {
            String url = oAuth2Service.getLinkedinAuthUrl("li-state-789");

            assertThat(url).startsWith("https://www.linkedin.com/oauth/v2/authorization");
            assertThat(url).contains("client_id=linkedin-client-id");
            assertThat(url).contains("response_type=code");
            assertThat(url).contains("scope=openid%20profile%20email");
            assertThat(url).contains("state=li-state-789");
            assertThat(url).contains("redirect_uri=");
        }

        @Test
        @DisplayName("Should include correct redirect URI path for Google")
        void getGoogleAuthUrl_ShouldIncludeCorrectCallbackPath() {
            String url = oAuth2Service.getGoogleAuthUrl("state");

            // The redirect_uri should contain the encoded callback path
            String expectedCallback = "https://example.com/api/v1/admin/auth/oauth2/callback/google";
            assertThat(url).contains("redirect_uri=" + java.net.URLEncoder.encode(expectedCallback, java.nio.charset.StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("Should include correct redirect URI path for GitHub")
        void getGithubAuthUrl_ShouldIncludeCorrectCallbackPath() {
            String url = oAuth2Service.getGithubAuthUrl("state");

            String expectedCallback = "https://example.com/api/v1/admin/auth/oauth2/callback/github";
            assertThat(url).contains("redirect_uri=" + java.net.URLEncoder.encode(expectedCallback, java.nio.charset.StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("Should include correct redirect URI path for LinkedIn")
        void getLinkedinAuthUrl_ShouldIncludeCorrectCallbackPath() {
            String url = oAuth2Service.getLinkedinAuthUrl("state");

            String expectedCallback = "https://example.com/api/v1/admin/auth/oauth2/callback/linkedin";
            assertThat(url).contains("redirect_uri=" + java.net.URLEncoder.encode(expectedCallback, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    // ==================== Store State ====================

    @Nested
    @DisplayName("storeState")
    class StoreState {

        @Test
        @DisplayName("Should store OAuth2 state in Redis with TTL")
        void storeState_ShouldSetValueInRedis() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.set(eq("oauth2:state:my-state"), eq("1"), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(oAuth2Service.storeState("my-state"))
                    .expectNext(true)
                    .verifyComplete();

            verify(valueOperations).set(eq("oauth2:state:my-state"), eq("1"), any(Duration.class));
        }
    }

    // ==================== Validate and Consume State ====================

    @Nested
    @DisplayName("validateAndConsumeState")
    class ValidateAndConsumeState {

        @Test
        @DisplayName("Should return false when state is null")
        void validateAndConsumeState_ShouldReturnFalse_WhenStateNull() {
            StepVerifier.create(oAuth2Service.validateAndConsumeState(null))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("Should return false when state is blank")
        void validateAndConsumeState_ShouldReturnFalse_WhenStateBlank() {
            StepVerifier.create(oAuth2Service.validateAndConsumeState("   "))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("Should return false when state is empty string")
        void validateAndConsumeState_ShouldReturnFalse_WhenStateEmpty() {
            StepVerifier.create(oAuth2Service.validateAndConsumeState(""))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("Should return true when state exists in Redis")
        void validateAndConsumeState_ShouldReturnTrue_WhenStateExists() {
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(Flux.just("1"));

            StepVerifier.create(oAuth2Service.validateAndConsumeState("valid-state"))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("Should return false when state does not exist in Redis")
        void validateAndConsumeState_ShouldReturnFalse_WhenStateNotFound() {
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(Flux.empty());

            StepVerifier.create(oAuth2Service.validateAndConsumeState("unknown-state"))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    // ==================== Link Account ====================

    @Nested
    @DisplayName("linkAccount")
    class LinkAccount {

        @Test
        @DisplayName("Should return existing account when already linked to same user")
        void linkAccount_ShouldReturnExisting_WhenLinkedToSameUser() {
            Long userId = 100L;
            UserSocialAccount existing = UserSocialAccount.builder()
                    .id(10L)
                    .userId(userId)
                    .provider("google")
                    .providerId("goog-123")
                    .providerEmail("test@example.com")
                    .build();

            when(socialAccountRepository.findByProviderAndProviderId("google", "goog-123"))
                    .thenReturn(Mono.just(existing));

            StepVerifier.create(oAuth2Service.linkAccount(userId, "google", "goog-123",
                            "test@example.com", "Test User", "https://avatar.url"))
                    .assertNext(account -> {
                        assertThat(account.getId()).isEqualTo(10L);
                        assertThat(account.getUserId()).isEqualTo(userId);
                        assertThat(account.getProvider()).isEqualTo("google");
                    })
                    .verifyComplete();

            verify(socialAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should return CONFLICT error when account is linked to different user")
        void linkAccount_ShouldThrowConflict_WhenLinkedToDifferentUser() {
            Long requestingUserId = 100L;
            Long existingUserId = 200L;
            UserSocialAccount existing = UserSocialAccount.builder()
                    .id(10L)
                    .userId(existingUserId)
                    .provider("github")
                    .providerId("gh-456")
                    .build();

            when(socialAccountRepository.findByProviderAndProviderId("github", "gh-456"))
                    .thenReturn(Mono.just(existing));

            StepVerifier.create(oAuth2Service.linkAccount(requestingUserId, "github", "gh-456",
                            "test@example.com", "Test User", null))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(ResponseStatusException.class);
                        ResponseStatusException rse = (ResponseStatusException) error;
                        assertThat(rse.getStatusCode().value()).isEqualTo(409);
                        assertThat(rse.getReason()).contains("github");
                        assertThat(rse.getReason()).contains("already linked");
                    })
                    .verify();
        }

        @Test
        @DisplayName("Should create new social account when no existing link found")
        void linkAccount_ShouldCreateNew_WhenNoExistingLink() {
            Long userId = 100L;
            long generatedId = 999L;

            when(socialAccountRepository.findByProviderAndProviderId("google", "goog-new"))
                    .thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(generatedId);
            when(socialAccountRepository.save(any(UserSocialAccount.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(auditService.logOAuth2AccountLinked(userId, "new@example.com", "google"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(oAuth2Service.linkAccount(userId, "google", "goog-new",
                            "new@example.com", "New User", "https://avatar.url"))
                    .assertNext(account -> {
                        assertThat(account.getId()).isEqualTo(generatedId);
                        assertThat(account.getUserId()).isEqualTo(userId);
                        assertThat(account.getProvider()).isEqualTo("google");
                        assertThat(account.getProviderId()).isEqualTo("goog-new");
                        assertThat(account.getProviderEmail()).isEqualTo("new@example.com");
                        assertThat(account.getDisplayName()).isEqualTo("New User");
                        assertThat(account.getAvatarUrl()).isEqualTo("https://avatar.url");
                        assertThat(account.getLinkedAt()).isNotNull();
                    })
                    .verifyComplete();

            verify(socialAccountRepository).save(any(UserSocialAccount.class));
            verify(auditService).logOAuth2AccountLinked(userId, "new@example.com", "google");
        }
    }

    // ==================== Unlink Account ====================

    @Nested
    @DisplayName("unlinkAccount")
    class UnlinkAccount {

        @Test
        @DisplayName("Should unlink account when user has a password")
        void unlinkAccount_ShouldDelete_WhenUserHasPassword() {
            User userWithPassword = User.builder()
                    .id(100L)
                    .email("test@example.com")
                    .passwordHash("$2a$10$hashed")
                    .build();

            when(userRepository.findById(100L)).thenReturn(Mono.just(userWithPassword));
            when(socialAccountRepository.deleteByUserIdAndProvider(100L, "google"))
                    .thenReturn(Mono.empty());
            when(auditService.logOAuth2AccountUnlinked(100L, "test@example.com", "google"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(oAuth2Service.unlinkAccount(100L, "google"))
                    .verifyComplete();

            verify(socialAccountRepository).deleteByUserIdAndProvider(100L, "google");
            verify(auditService).logOAuth2AccountUnlinked(100L, "test@example.com", "google");
            verify(socialAccountRepository, never()).countByUserId(anyLong());
        }

        @Test
        @DisplayName("Should unlink account when user has no password but has multiple providers")
        void unlinkAccount_ShouldDelete_WhenNoPasswordButMultipleProviders() {
            User userWithoutPassword = User.builder()
                    .id(100L)
                    .email("test@example.com")
                    .passwordHash(null)
                    .build();

            when(userRepository.findById(100L)).thenReturn(Mono.just(userWithoutPassword));
            when(socialAccountRepository.deleteByUserIdAndProviderIfNotLast(100L, "google"))
                    .thenReturn(Mono.just(1L));
            when(auditService.logOAuth2AccountUnlinked(100L, "test@example.com", "google"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(oAuth2Service.unlinkAccount(100L, "google"))
                    .verifyComplete();

            verify(socialAccountRepository).deleteByUserIdAndProviderIfNotLast(100L, "google");
        }

        @Test
        @DisplayName("Should throw when unlinking last provider and user has no password")
        void unlinkAccount_ShouldThrow_WhenLastProviderAndNoPassword() {
            User userWithoutPassword = User.builder()
                    .id(100L)
                    .email("test@example.com")
                    .passwordHash(null)
                    .build();

            when(userRepository.findById(100L)).thenReturn(Mono.just(userWithoutPassword));
            // Conditional delete refuses: this is the last social account
            when(socialAccountRepository.deleteByUserIdAndProviderIfNotLast(100L, "github"))
                    .thenReturn(Mono.just(0L));
            when(auditService.logOAuth2AccountUnlinked(100L, "test@example.com", "github"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(oAuth2Service.unlinkAccount(100L, "github"))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(ResponseStatusException.class);
                        ResponseStatusException rse = (ResponseStatusException) error;
                        assertThat(rse.getStatusCode().value()).isEqualTo(400);
                        assertThat(rse.getReason()).contains("Cannot unlink last login method");
                    })
                    .verify();

            verify(socialAccountRepository, never()).deleteByUserIdAndProvider(anyLong(), anyString());
        }

        @Test
        @DisplayName("Should unlink account when user has blank password but multiple providers")
        void unlinkAccount_ShouldDelete_WhenBlankPasswordAndMultipleProviders() {
            User userWithBlankPassword = User.builder()
                    .id(100L)
                    .email("test@example.com")
                    .passwordHash("   ")
                    .build();

            when(userRepository.findById(100L)).thenReturn(Mono.just(userWithBlankPassword));
            when(socialAccountRepository.deleteByUserIdAndProviderIfNotLast(100L, "linkedin"))
                    .thenReturn(Mono.just(1L));
            when(auditService.logOAuth2AccountUnlinked(100L, "test@example.com", "linkedin"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(oAuth2Service.unlinkAccount(100L, "linkedin"))
                    .verifyComplete();

            verify(socialAccountRepository).deleteByUserIdAndProviderIfNotLast(100L, "linkedin");
        }

        @Test
        @DisplayName("Should throw when unlinking last provider and user has blank password")
        void unlinkAccount_ShouldThrow_WhenLastProviderAndBlankPassword() {
            User userWithBlankPassword = User.builder()
                    .id(100L)
                    .email("test@example.com")
                    .passwordHash("")
                    .build();

            when(userRepository.findById(100L)).thenReturn(Mono.just(userWithBlankPassword));
            when(socialAccountRepository.deleteByUserIdAndProviderIfNotLast(100L, "google"))
                    .thenReturn(Mono.just(0L));
            when(auditService.logOAuth2AccountUnlinked(100L, "test@example.com", "google"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(oAuth2Service.unlinkAccount(100L, "google"))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(ResponseStatusException.class);
                        ResponseStatusException rse = (ResponseStatusException) error;
                        assertThat(rse.getStatusCode().value()).isEqualTo(400);
                    })
                    .verify();
        }

        @Test
        @DisplayName("Should complete empty when user not found")
        void unlinkAccount_ShouldCompleteEmpty_WhenUserNotFound() {
            when(userRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(oAuth2Service.unlinkAccount(999L, "google"))
                    .verifyComplete();

            verify(socialAccountRepository, never()).deleteByUserIdAndProvider(anyLong(), anyString());
        }
    }

    // ==================== findOrCreateUser (tested via handleXxxCallback scenarios) ====================
    // The handleGoogleCallback, handleGithubCallback, handleLinkedinCallback methods rely on
    // external HTTP calls (WebClient) which are hard to mock without injecting the WebClient.
    // However, we can test the findOrCreateUser logic indirectly through linkAccount tests
    // and by testing the private method behavior through reflection or integration tests.
    //
    // The callback methods are best tested via integration or @WebFluxTest with WireMock.
    // Below we focus on the remaining testable behavior.

    // ==================== Auth URL edge cases ====================

    @Nested
    @DisplayName("Auth URL edge cases")
    class AuthUrlEdgeCases {

        @Test
        @DisplayName("Should handle empty state parameter in Google URL")
        void getGoogleAuthUrl_ShouldHandleEmptyState() {
            String url = oAuth2Service.getGoogleAuthUrl("");
            assertThat(url).contains("state=");
            assertThat(url).contains("client_id=google-client-id");
        }

        @Test
        @DisplayName("Should handle empty state parameter in GitHub URL")
        void getGithubAuthUrl_ShouldHandleEmptyState() {
            String url = oAuth2Service.getGithubAuthUrl("");
            assertThat(url).contains("state=");
            assertThat(url).contains("client_id=github-client-id");
        }

        @Test
        @DisplayName("Should handle empty state parameter in LinkedIn URL")
        void getLinkedinAuthUrl_ShouldHandleEmptyState() {
            String url = oAuth2Service.getLinkedinAuthUrl("");
            assertThat(url).contains("state=");
            assertThat(url).contains("client_id=linkedin-client-id");
        }

        @Test
        @DisplayName("Should URL-encode Unicode characters in state")
        void getGoogleAuthUrl_ShouldEncodeUnicodeState() {
            String url = oAuth2Service.getGoogleAuthUrl("state-\u00e9\u00e0\u00fc");
            // Should not throw, and the state should be encoded
            assertThat(url).contains("state=");
            assertThat(url).doesNotContain("\u00e9");
        }
    }

    // ==================== linkAccount edge cases ====================

    @Nested
    @DisplayName("linkAccount edge cases")
    class LinkAccountEdgeCases {

        @Test
        @DisplayName("Should set linkedAt timestamp on new account")
        void linkAccount_ShouldSetLinkedAtTimestamp() {
            Long userId = 100L;
            LocalDateTime beforeTest = LocalDateTime.now().minusSeconds(1);

            when(socialAccountRepository.findByProviderAndProviderId("google", "new-id"))
                    .thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(500L);
            when(socialAccountRepository.save(any(UserSocialAccount.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(auditService.logOAuth2AccountLinked(userId, "user@test.com", "google"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(oAuth2Service.linkAccount(userId, "google", "new-id",
                            "user@test.com", "User", null))
                    .assertNext(account -> {
                        assertThat(account.getLinkedAt()).isAfter(beforeTest);
                        assertThat(account.getAvatarUrl()).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle null avatar URL when creating account")
        void linkAccount_ShouldHandleNullAvatar() {
            when(socialAccountRepository.findByProviderAndProviderId("github", "gh-id"))
                    .thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(600L);
            when(socialAccountRepository.save(any(UserSocialAccount.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(auditService.logOAuth2AccountLinked(200L, "user@test.com", "github"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(oAuth2Service.linkAccount(200L, "github", "gh-id",
                            "user@test.com", null, null))
                    .assertNext(account -> {
                        assertThat(account.getDisplayName()).isNull();
                        assertThat(account.getAvatarUrl()).isNull();
                    })
                    .verifyComplete();
        }
    }

    // ==================== Unlink Account edge cases ====================

    @Nested
    @DisplayName("unlinkAccount edge cases")
    class UnlinkAccountEdgeCases {

        @Test
        @DisplayName("Should unlink successfully when user has password and zero other providers")
        void unlinkAccount_ShouldDelete_WhenUserHasPasswordAndZeroOtherProviders() {
            User userWithPassword = User.builder()
                    .id(100L)
                    .email("test@example.com")
                    .passwordHash("$2a$10$hashed")
                    .build();

            when(userRepository.findById(100L)).thenReturn(Mono.just(userWithPassword));
            when(socialAccountRepository.deleteByUserIdAndProvider(100L, "google"))
                    .thenReturn(Mono.empty());
            when(auditService.logOAuth2AccountUnlinked(100L, "test@example.com", "google"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(oAuth2Service.unlinkAccount(100L, "google"))
                    .verifyComplete();

            // Should not check count when user has a password
            verify(socialAccountRepository, never()).countByUserId(anyLong());
            verify(socialAccountRepository).deleteByUserIdAndProvider(100L, "google");
        }
    }

    // ==================== storeState edge cases ====================

    @Nested
    @DisplayName("storeState edge cases")
    class StoreStateEdgeCases {

        @Test
        @DisplayName("Should use correct Redis key prefix for state storage")
        void storeState_ShouldUseCorrectKeyPrefix() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.set(anyString(), eq("1"), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(oAuth2Service.storeState("abc-123-def"))
                    .expectNext(true)
                    .verifyComplete();

            verify(valueOperations).set(eq("oauth2:state:abc-123-def"), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("Should propagate Redis error on store failure")
        void storeState_ShouldPropagateError_WhenRedisFails() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.set(anyString(), eq("1"), any(Duration.class)))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection failed")));

            StepVerifier.create(oAuth2Service.storeState("fail-state"))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    // ==================== validateAndConsumeState edge cases ====================

    @Nested
    @DisplayName("validateAndConsumeState edge cases")
    class ValidateAndConsumeStateEdgeCases {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("Should propagate Redis error during state validation")
        void validateAndConsumeState_ShouldPropagateError_WhenRedisFails() {
            when(redisTemplate.execute(any(RedisScript.class), anyList()))
                    .thenReturn(Flux.error(new RuntimeException("Redis down")));

            StepVerifier.create(oAuth2Service.validateAndConsumeState("some-state"))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }
}
