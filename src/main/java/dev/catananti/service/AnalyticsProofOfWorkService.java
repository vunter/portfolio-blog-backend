package dev.catananti.service;

import dev.catananti.dto.AnalyticsChallengeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SEC-AH-01: Proof-of-Work challenge service for analytics endpoints.
 * Issues SHA-256 challenges that clients must solve before submitting events.
 * Makes bulk spam computationally expensive while adding negligible cost for real users.
 */
@Service
@Slf4j
public class AnalyticsProofOfWorkService {

    private static final String CHALLENGE_KEY_PREFIX = "analytics:pow:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final int difficulty;
    private final Duration challengeTtl;

    public AnalyticsProofOfWorkService(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
            @Value("${analytics.pow.difficulty:16}") int difficulty,
            @Value("${analytics.pow.ttl-seconds:300}") int ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.difficulty = difficulty;
        this.challengeTtl = Duration.ofSeconds(ttlSeconds);
        log.info("PoW service initialised (difficulty={}, ttl={}s)", difficulty, ttlSeconds);
    }

    /**
     * Issue a new challenge: random nonce stored in Redis with TTL.
     */
    public Mono<AnalyticsChallengeResponse> issueChallenge() {
        String challengeId = UUID.randomUUID().toString();
        byte[] nonceBytes = new byte[16];
        SECURE_RANDOM.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        Instant expiresAt = Instant.now().plus(challengeTtl);

        String redisKey = CHALLENGE_KEY_PREFIX + challengeId;

        return redisTemplate.opsForValue()
                .set(redisKey, nonce, challengeTtl)
                .thenReturn(AnalyticsChallengeResponse.builder()
                        .challengeId(challengeId)
                        .nonce(nonce)
                        .difficulty(difficulty)
                        .expiresAt(expiresAt)
                        .build())
                .doOnSuccess(c -> log.debug("PoW challenge issued: {}", challengeId));
    }

    /**
     * Verify a challenge solution. Single-use: deletes the challenge from Redis on lookup.
     *
     * @return Mono.empty() if valid, Mono.error() if invalid
     */
    public Mono<Void> verifySolution(String challengeId, String solution) {
        if (challengeId == null || solution == null) {
            return Mono.error(new IllegalArgumentException("error.pow_fields_required"));
        }

        String redisKey = CHALLENGE_KEY_PREFIX + challengeId;

        return redisTemplate.opsForValue().getAndDelete(redisKey)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("error.pow_challenge_expired")))
                .flatMap(nonce -> {
                    if (isValidSolution(nonce, solution, difficulty)) {
                        log.debug("PoW solution verified for challenge {}", challengeId);
                        return Mono.empty();
                    }
                    log.warn("Invalid PoW solution for challenge {}", challengeId);
                    return Mono.error(new IllegalArgumentException("error.pow_invalid_solution"));
                });
    }

    /**
     * Check that SHA-256(nonce + solution) has the required number of leading zero bits.
     */
    static boolean isValidSolution(String nonce, String solution, int difficulty) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((nonce + solution).getBytes(StandardCharsets.UTF_8));

            int zeroBitsNeeded = difficulty;
            for (byte b : hash) {
                if (zeroBitsNeeded <= 0) break;
                if (zeroBitsNeeded >= 8) {
                    if (b != 0) return false;
                    zeroBitsNeeded -= 8;
                } else {
                    // Check remaining bits in this byte
                    int mask = (0xFF << (8 - zeroBitsNeeded)) & 0xFF;
                    if ((b & mask) != 0) return false;
                    zeroBitsNeeded = 0;
                }
            }
            return true;
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available", e);
            return false;
        }
    }
}
