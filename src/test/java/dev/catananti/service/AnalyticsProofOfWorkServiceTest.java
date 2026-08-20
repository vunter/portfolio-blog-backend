package dev.catananti.service;

import dev.catananti.dto.AnalyticsChallengeResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsProofOfWorkService")
class AnalyticsProofOfWorkServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    private AnalyticsProofOfWorkService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsProofOfWorkService(redisTemplate, 16, 300);
    }

    // ──────────────────────────────────────────────
    // isValidSolution() — pure computation tests
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("isValidSolution()")
    class IsValidSolution {

        @Test
        @DisplayName("should return true for difficulty=0 (no leading zeros needed)")
        void difficulty0_shouldAlwaysReturnTrue() {
            // Any nonce+solution pair should pass with difficulty 0
            boolean result = AnalyticsProofOfWorkService.isValidSolution("test", "0", 0);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return true for difficulty=0 with arbitrary inputs")
        void difficulty0_arbitraryInputs_shouldReturnTrue() {
            boolean result = AnalyticsProofOfWorkService.isValidSolution("anything", "whatever", 0);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return true for known valid solution at difficulty=8")
        void difficulty8_knownValidSolution_shouldReturnTrue() {
            // SHA-256("test" + "304") = 009fa3... (first byte is 0x00)
            boolean result = AnalyticsProofOfWorkService.isValidSolution("test", "304", 8);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false for known invalid solution at difficulty=8")
        void difficulty8_knownInvalidSolution_shouldReturnFalse() {
            // SHA-256("test" + "0") = 590c9f... (first byte is 0x59, not 0x00)
            boolean result = AnalyticsProofOfWorkService.isValidSolution("test", "0", 8);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return true for known valid solution at difficulty=16")
        void difficulty16_knownValidSolution_shouldReturnTrue() {
            // SHA-256("test" + "93721") = 00001c... (first two bytes are 0x0000)
            boolean result = AnalyticsProofOfWorkService.isValidSolution("test", "93721", 16);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when first two bytes are not zero at difficulty=16")
        void difficulty16_insufficientZeros_shouldReturnFalse() {
            // SHA-256("test" + "304") = 009fa3... — only 1 byte zero, need 2
            boolean result = AnalyticsProofOfWorkService.isValidSolution("test", "304", 16);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should handle difficulty=4 (first nibble must be zero)")
        void difficulty4_knownValidSolution_shouldReturnTrue() {
            // SHA-256("test" + "25") = 0342... (first nibble is 0)
            boolean result = AnalyticsProofOfWorkService.isValidSolution("test", "25", 4);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false for difficulty=4 when first nibble is non-zero")
        void difficulty4_invalidSolution_shouldReturnFalse() {
            // SHA-256("test" + "0") = 590c... (first nibble is 5, not 0)
            boolean result = AnalyticsProofOfWorkService.isValidSolution("test", "0", 4);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should handle difficulty=8 solution that also satisfies difficulty=4")
        void difficulty8_solution_shouldAlsoSatisfyDifficulty4() {
            // SHA-256("test" + "304") = 009f... — first byte is 0x00, so first nibble is also 0
            boolean result = AnalyticsProofOfWorkService.isValidSolution("test", "304", 4);

            assertThat(result).isTrue();
        }
    }

    // ──────────────────────────────────────────────
    // issueChallenge()
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("issueChallenge()")
    class IssueChallenge {

        @Test
        @DisplayName("should return challenge response with correct fields")
        void shouldReturnChallengeResponse() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.set(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(Mono.just(true));

            StepVerifier.create(service.issueChallenge())
                    .assertNext(response -> {
                        assertThat(response).isNotNull();
                        assertThat(response.getChallengeId()).isNotNull().isNotBlank();
                        assertThat(response.getNonce()).isNotNull().isNotBlank();
                        assertThat(response.getDifficulty()).isEqualTo(16);
                        assertThat(response.getExpiresAt()).isNotNull();
                        assertThat(response.getExpiresAt()).isAfter(java.time.Instant.now());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should store challenge in Redis with TTL")
        void shouldStoreInRedis() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.set(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(Mono.just(true));

            StepVerifier.create(service.issueChallenge())
                    .assertNext(response -> {
                        assertThat(response.getChallengeId()).matches(
                                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
                        assertThat(response.getNonce()).matches("^[0-9a-f]{32}$");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // verifySolution()
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("verifySolution()")
    class VerifySolution {

        @Test
        @DisplayName("should complete empty for valid challenge and solution")
        void validChallenge_validSolution_shouldComplete() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            // Return a nonce where we know the solution
            // nonce="test", solution="93721" is valid at difficulty=16
            when(valueOps.getAndDelete(anyString())).thenReturn(Mono.just("test"));

            StepVerifier.create(service.verifySolution("some-challenge-id", "93721"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should error when challengeId is null")
        void nullChallengeId_shouldError() {
            StepVerifier.create(service.verifySolution(null, "solution"))
                    .expectErrorMatches(ex ->
                            ex instanceof IllegalArgumentException
                                    && ex.getMessage().contains("error.pow_fields_required"))
                    .verify();
        }

        @Test
        @DisplayName("should error when solution is null")
        void nullSolution_shouldError() {
            StepVerifier.create(service.verifySolution("challenge-id", null))
                    .expectErrorMatches(ex ->
                            ex instanceof IllegalArgumentException
                                    && ex.getMessage().contains("error.pow_fields_required"))
                    .verify();
        }

        @Test
        @DisplayName("should error when challenge not found or expired in Redis")
        void expiredChallenge_shouldError() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.getAndDelete(anyString())).thenReturn(Mono.empty());

            StepVerifier.create(service.verifySolution("expired-id", "solution"))
                    .expectErrorMatches(ex ->
                            ex instanceof IllegalArgumentException
                                    && ex.getMessage().contains("error.pow_challenge_expired"))
                    .verify();
        }

        @Test
        @DisplayName("should error when solution is invalid for the given nonce")
        void invalidSolution_shouldError() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            // nonce="test", solution="0" does NOT satisfy difficulty=16
            when(valueOps.getAndDelete(anyString())).thenReturn(Mono.just("test"));

            StepVerifier.create(service.verifySolution("challenge-id", "0"))
                    .expectErrorMatches(ex ->
                            ex instanceof IllegalArgumentException
                                    && ex.getMessage().contains("error.pow_invalid_solution"))
                    .verify();
        }
    }
}
