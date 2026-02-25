package dev.catananti.service;

import dev.catananti.config.ResilienceConfig;
import dev.catananti.entity.User;
import dev.catananti.repository.UserRepository;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private ResilienceConfig resilience;

    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        lenient().when(resilience.getRedisTimeout()).thenReturn(Duration.ofSeconds(5));
        loginAttemptService = new LoginAttemptService(
                redisTemplate, userRepository, emailService, resilience,
                5,  // maxAttempts
                15, // attemptWindowMinutes
                5   // progressiveLockoutBaseMinutes
        );
    }

    @Nested
    @DisplayName("isBlocked")
    class IsBlocked {

        @Test
        @DisplayName("Should return true when lockout key exists")
        void shouldReturnTrue_WhenLocked() {
            when(redisTemplate.hasKey("lockout:user@test.com")).thenReturn(Mono.just(true));

            StepVerifier.create(loginAttemptService.isBlocked("user@test.com"))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when lockout key does not exist")
        void shouldReturnFalse_WhenNotLocked() {
            when(redisTemplate.hasKey("lockout:user@test.com")).thenReturn(Mono.just(false));

            StepVerifier.create(loginAttemptService.isBlocked("user@test.com"))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false on Redis error")
        void shouldReturnFalse_OnRedisError() {
            when(redisTemplate.hasKey("lockout:user@test.com"))
                    .thenReturn(Mono.error(new RuntimeException("Redis down")));

            StepVerifier.create(loginAttemptService.isBlocked("user@test.com"))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getRemainingLockoutTime")
    class GetRemainingLockoutTime {

        @Test
        @DisplayName("Should return remaining seconds when locked")
        void shouldReturnRemainingSeconds() {
            when(redisTemplate.getExpire("lockout:user@test.com"))
                    .thenReturn(Mono.just(Duration.ofMinutes(3)));

            StepVerifier.create(loginAttemptService.getRemainingLockoutTime("user@test.com"))
                    .expectNext(180L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return 0 when not locked")
        void shouldReturnZero_WhenNotLocked() {
            when(redisTemplate.getExpire("lockout:user@test.com"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(loginAttemptService.getRemainingLockoutTime("user@test.com"))
                    .expectNext(0L)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("recordFailedAttempt")
    class RecordFailedAttempt {

        @Test
        @DisplayName("Should set expiry on first attempt")
        void shouldSetExpiry_OnFirstAttempt() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("login_attempt:user@test.com")).thenReturn(Mono.just(1L));
            when(redisTemplate.expire("login_attempt:user@test.com", Duration.ofMinutes(15)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(loginAttemptService.recordFailedAttempt("user@test.com"))
                    .expectNext(1)
                    .verifyComplete();

            verify(redisTemplate).expire("login_attempt:user@test.com", Duration.ofMinutes(15));
        }

        @Test
        @DisplayName("Should return attempt count under threshold")
        void shouldReturnCount_UnderThreshold() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("login_attempt:user@test.com")).thenReturn(Mono.just(3L));

            StepVerifier.create(loginAttemptService.recordFailedAttempt("user@test.com"))
                    .expectNext(3)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should trigger lockout when max attempts reached")
        void shouldTriggerLockout_WhenMaxAttemptsReached() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("login_attempt:user@test.com")).thenReturn(Mono.just(5L));
            when(valueOps.set(eq("lockout:user@test.com"), eq("5"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            // Lockout notification
            when(valueOps.setIfAbsent(startsWith("lockout_notified:"), eq("1"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(userRepository.findByEmail("user@test.com"))
                    .thenReturn(Mono.just(User.builder()
                            .email("user@test.com")
                            .name("Test")
                            .build()));
            when(emailService.sendAccountLockoutNotification(anyString(), anyString(), anyInt(), anyLong(), any()))
                    .thenReturn(Mono.empty());

            StepVerifier.create(loginAttemptService.recordFailedAttempt("user@test.com", "192.168.1.1"))
                    .expectNext(5)
                    .verifyComplete();

            verify(valueOps).set(eq("lockout:user@test.com"), eq("5"), any(Duration.class));
        }

        @Test
        @DisplayName("Should fall back to local cache on Redis error")
        void shouldReturnZero_OnRedisError() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("login_attempt:user@test.com"))
                    .thenReturn(Mono.error(new RuntimeException("Redis down")));

            StepVerifier.create(loginAttemptService.recordFailedAttempt("user@test.com"))
                    .expectNext(1)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("clearFailedAttempts")
    class ClearFailedAttempts {

        @Test
        @DisplayName("Should delete attempt and lockout keys")
        void shouldDeleteBothKeys() {
            when(redisTemplate.delete("login_attempt:user@test.com")).thenReturn(Mono.just(1L));
            when(redisTemplate.delete("lockout:user@test.com")).thenReturn(Mono.just(1L));

            StepVerifier.create(loginAttemptService.clearFailedAttempts("user@test.com"))
                    .verifyComplete();

            verify(redisTemplate).delete("login_attempt:user@test.com");
            verify(redisTemplate).delete("lockout:user@test.com");
        }

        @Test
        @DisplayName("Should complete gracefully on Redis error")
        void shouldCompleteGracefully_OnRedisError() {
            when(redisTemplate.delete("login_attempt:user@test.com"))
                    .thenReturn(Mono.error(new RuntimeException("Redis down")));
            // The second delete is evaluated eagerly as a Java argument to .then(),
            // so it must return a non-null Mono even though it won't be subscribed
            when(redisTemplate.delete("lockout:user@test.com"))
                    .thenReturn(Mono.just(1L));

            StepVerifier.create(loginAttemptService.clearFailedAttempts("user@test.com"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getFailedAttempts")
    class GetFailedAttempts {

        @Test
        @DisplayName("Should return current attempt count")
        void shouldReturnAttemptCount() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("login_attempt:user@test.com")).thenReturn(Mono.just("3"));

            StepVerifier.create(loginAttemptService.getFailedAttempts("user@test.com"))
                    .expectNext(3)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return 0 when no attempts recorded")
        void shouldReturnZero_WhenNoAttempts() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("login_attempt:user@test.com")).thenReturn(Mono.empty());

            StepVerifier.create(loginAttemptService.getFailedAttempts("user@test.com"))
                    .expectNext(0)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getRemainingAttempts")
    class GetRemainingAttempts {

        @Test
        @DisplayName("Should return remaining attempts")
        void shouldReturnRemainingAttempts() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("login_attempt:user@test.com")).thenReturn(Mono.just("2"));

            StepVerifier.create(loginAttemptService.getRemainingAttempts("user@test.com"))
                    .expectNext(3)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return 0 when all attempts exhausted")
        void shouldReturnZero_WhenExhausted() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("login_attempt:user@test.com")).thenReturn(Mono.just("7"));

            StepVerifier.create(loginAttemptService.getRemainingAttempts("user@test.com"))
                    .expectNext(0)
                    .verifyComplete();
        }
    }
}
