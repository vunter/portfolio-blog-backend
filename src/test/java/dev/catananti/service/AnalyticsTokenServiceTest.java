package dev.catananti.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsTokenService")
class AnalyticsTokenServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    private AnalyticsTokenService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsTokenService(redisTemplate, 1800);
    }

    // ──────────────────────────────────────────────
    // issueToken()
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("issueToken()")
    class IssueToken {

        @Test
        @DisplayName("should return token response with non-null token and future expiresAt")
        void shouldReturnTokenResponse() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.set(anyString(), anyString(), any())).thenReturn(Mono.just(true));

            StepVerifier.create(service.issueToken())
                    .assertNext(response -> {
                        assertThat(response).isNotNull();
                        assertThat(response.getToken()).isNotNull().isNotBlank();
                        assertThat(response.getToken()).matches(
                                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
                        assertThat(response.getExpiresAt()).isNotNull();
                        assertThat(response.getExpiresAt()).isAfter(Instant.now());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should store token in Redis with TTL")
        void shouldStoreTokenInRedis() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.set(anyString(), anyString(), any())).thenReturn(Mono.just(true));

            StepVerifier.create(service.issueToken())
                    .assertNext(response -> {
                        assertThat(response.getToken()).isNotBlank();
                        // expiresAt should be approximately 1800 seconds in the future
                        Instant expectedMin = Instant.now().plusSeconds(1790);
                        Instant expectedMax = Instant.now().plusSeconds(1810);
                        assertThat(response.getExpiresAt()).isBetween(expectedMin, expectedMax);
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // validateAndConsume()
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("validateAndConsume()")
    class ValidateAndConsume {

        @Test
        @DisplayName("should error when token is null")
        void nullToken_shouldError() {
            StepVerifier.create(service.validateAndConsume(null))
                    .expectErrorMatches(ex ->
                            ex instanceof IllegalArgumentException
                                    && ex.getMessage().contains("error.analytics_token_required"))
                    .verify();
        }

        @Test
        @DisplayName("should error when token is blank")
        void blankToken_shouldError() {
            StepVerifier.create(service.validateAndConsume("   "))
                    .expectErrorMatches(ex ->
                            ex instanceof IllegalArgumentException
                                    && ex.getMessage().contains("error.analytics_token_required"))
                    .verify();
        }

        @Test
        @DisplayName("should error when token is empty string")
        void emptyToken_shouldError() {
            StepVerifier.create(service.validateAndConsume(""))
                    .expectErrorMatches(ex ->
                            ex instanceof IllegalArgumentException
                                    && ex.getMessage().contains("error.analytics_token_required"))
                    .verify();
        }

        @Test
        @DisplayName("should complete when token is valid and exists in Redis")
        void validToken_shouldComplete() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.getAndDelete(anyString())).thenReturn(Mono.just("1"));

            StepVerifier.create(service.validateAndConsume("valid-token-uuid"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should error when token is missing or expired in Redis")
        void expiredToken_shouldError() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.getAndDelete(anyString())).thenReturn(Mono.empty());

            StepVerifier.create(service.validateAndConsume("expired-token"))
                    .expectErrorMatches(ex ->
                            ex instanceof IllegalArgumentException
                                    && ex.getMessage().contains("error.analytics_token_invalid"))
                    .verify();
        }
    }
}
