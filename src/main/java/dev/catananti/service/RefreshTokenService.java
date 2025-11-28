package dev.catananti.service;

import dev.catananti.entity.RefreshToken;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.RefreshTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final IdService idService;

    @Value("${jwt.refresh-expiration:604800000}") // 7 days default
    private long refreshTokenExpirationMs;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Base64.Encoder base64Encoder = Base64.getUrlEncoder().withoutPadding();

    @Transactional
    public Mono<RefreshToken> createRefreshToken(Long userId) {
        return refreshTokenRepository.revokeAllByUserId(userId)
                .then(Mono.defer(() -> {
                    String plainToken = generateSecureToken();
                    // SEC-04: Store SHA-256 hash of the token in the database
                    String hashedToken = hashToken(plainToken);
                    
                    RefreshToken refreshToken = RefreshToken.builder()
                            .id(idService.nextId())
                            .userId(userId)
                            .token(hashedToken)
                            .expiresAt(LocalDateTime.now().plusSeconds(refreshTokenExpirationMs / 1000))
                            .createdAt(LocalDateTime.now())
                            .revoked(false)
                            .build();

                    return refreshTokenRepository.save(refreshToken)
                            .map(saved -> {
                                // Return entity with plain token for the response (not persisted)
                                saved.setToken(plainToken);
                                return saved;
                            });
                }))
                .doOnSuccess(rt -> log.info("Refresh token created for user: {}", userId));
    }

    public Mono<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByTokenAndRevokedFalse(hashToken(token));
    }

    @Transactional
    public Mono<RefreshToken> verifyAndRotate(String token) {
        // SEC-04: Hash the incoming token before looking it up
        String hashedToken = hashToken(token);
        return refreshTokenRepository.findByToken(hashedToken)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.unauthorized")))
                .flatMap(refreshToken -> {
                    if (refreshToken.isRevoked()) {
                        // Token was already used - possible token theft, revoke all tokens for user
                        // TODO F-208: Log audit event when refresh token reuse detected (potential theft)
                        log.warn("Attempted reuse of revoked refresh token for user: {}", refreshToken.getUserId());
                        return refreshTokenRepository.revokeAllByUserId(refreshToken.getUserId())
                                .then(Mono.error(new SecurityException("error.unauthorized")));
                    }

                    if (refreshToken.isExpired()) {
                        return Mono.error(new SecurityException("error.unauthorized"));
                    }

                    // Revoke old token and create new one (token rotation)
                    refreshToken.setRevoked(true);
                    return refreshTokenRepository.save(refreshToken)
                            .then(createRefreshToken(refreshToken.getUserId()));
                });
    }

    @Transactional
    public Mono<Void> revokeToken(String token) {
        return refreshTokenRepository.findByToken(hashToken(token))
                .flatMap(refreshToken -> {
                    refreshToken.setRevoked(true);
                    return refreshTokenRepository.save(refreshToken);
                })
                .then()
                .doOnSuccess(v -> log.info("Refresh token revoked"));
    }

    @Transactional
    public Mono<Void> revokeAllUserTokens(Long userId) {
        return refreshTokenRepository.revokeAllByUserId(userId)
                .doOnSuccess(v -> log.info("All refresh tokens revoked for user: {}", userId));
    }

    // F-207: Use .block() instead of fire-and-forget .subscribe() —
    // @Scheduled runs on its own thread pool, so blocking is safe
    @Scheduled(fixedRateString = "${scheduling.refresh-token-cleanup-ms:3600000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void cleanupExpiredTokens() {
        try {
            refreshTokenRepository.deleteExpired(LocalDateTime.now())
                    .doOnSuccess(result -> log.info("Expired refresh tokens cleaned up"))
                    .block();
        } catch (Exception e) {
            log.error("Failed to cleanup expired refresh tokens: {}", e.getMessage());
        }
    }

    private String generateSecureToken() {
        byte[] randomBytes = new byte[64];
        secureRandom.nextBytes(randomBytes);
        return base64Encoder.encodeToString(randomBytes);
    }

    /**
     * SEC-04: Hash a token with SHA-256 for secure storage.
     * The plain token is returned to the client; only the hash is stored.
     */
    private String hashToken(String token) {
        return DigestUtils.sha256Hex(token);
    }
}
