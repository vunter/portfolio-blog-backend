package dev.catananti.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenBlacklistService")
class TokenBlacklistServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @InjectMocks
    private TokenBlacklistService tokenBlacklistService;

    // ──────────────────────────────────────────────
    // blacklist(jti, remainingMs)
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("blacklist()")
    class Blacklist {

        @Test
        @DisplayName("should store key in Redis with correct TTL and return true")
        void validCall_shouldStoreAndReturnTrue() {
            // Given
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.set(eq("jwt:blacklist:abc-123"), eq("1"), eq(Duration.ofMillis(60000))))
                    .thenReturn(Mono.just(true));

            // When / Then
            StepVerifier.create(tokenBlacklistService.blacklist("abc-123", 60000))
                    .expectNext(true)
                    .verifyComplete();

            verify(valueOperations).set("jwt:blacklist:abc-123", "1", Duration.ofMillis(60000));
        }

        @Test
        @DisplayName("should return false when jti is null")
        void nullJti_shouldReturnFalse() {
            StepVerifier.create(tokenBlacklistService.blacklist(null, 60000))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("should return false when jti is blank")
        void blankJti_shouldReturnFalse() {
            StepVerifier.create(tokenBlacklistService.blacklist("   ", 60000))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("should return false when remainingMs is zero")
        void zeroTtl_shouldReturnFalse() {
            StepVerifier.create(tokenBlacklistService.blacklist("abc-123", 0))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("should return false when remainingMs is negative")
        void negativeTtl_shouldReturnFalse() {
            StepVerifier.create(tokenBlacklistService.blacklist("abc-123", -5000))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("should return false and not propagate error when Redis fails")
        void redisError_shouldReturnFalseAndNotPropagate() {
            // Given
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.set(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

            // When / Then
            StepVerifier.create(tokenBlacklistService.blacklist("abc-123", 60000))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // isBlacklisted(jti)
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("isBlacklisted()")
    class IsBlacklisted {

        @Test
        @DisplayName("should return true when key exists in Redis")
        void keyExists_shouldReturnTrue() {
            // Given
            when(redisTemplate.hasKey("jwt:blacklist:abc-123")).thenReturn(Mono.just(true));

            // When / Then
            StepVerifier.create(tokenBlacklistService.isBlacklisted("abc-123"))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return false when key does not exist in Redis")
        void keyNotExists_shouldReturnFalse() {
            // Given
            when(redisTemplate.hasKey("jwt:blacklist:xyz-789")).thenReturn(Mono.just(false));

            // When / Then
            StepVerifier.create(tokenBlacklistService.isBlacklisted("xyz-789"))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return false when jti is null")
        void nullJti_shouldReturnFalse() {
            StepVerifier.create(tokenBlacklistService.isBlacklisted(null))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("should return false when jti is blank")
        void blankJti_shouldReturnFalse() {
            StepVerifier.create(tokenBlacklistService.isBlacklisted("   "))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("should fail-closed and return true when Redis errors")
        void redisError_shouldReturnTrueFailClosed() {
            // Given
            when(redisTemplate.hasKey("jwt:blacklist:abc-123"))
                    .thenReturn(Mono.error(new RuntimeException("Redis unavailable")));

            // When / Then — fail-closed: assume blacklisted
            StepVerifier.create(tokenBlacklistService.isBlacklisted("abc-123"))
                    .expectNext(true)
                    .verifyComplete();
        }
    }
}
