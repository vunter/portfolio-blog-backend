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
    private final AuditService auditService;

    @Value("${jwt.refresh-expiration:604800000}") // 7 days default
    private long refreshTokenExpirationMs;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Base64.Encoder base64Encoder = Base64.getUrlEncoder().withoutPadding();

    @Transactional
    public Mono<RefreshToken> createRefreshToken(Long userId) {
        return createRefreshToken(userId, null, null);
    }

    @Transactional
    public Mono<RefreshToken> createRefreshToken(Long userId, String ipAddress, String userAgent) {
        return refreshTokenRepository.revokeAllByUserId(userId)
                .then(Mono.defer(() -> {
                    String plainToken = generateSecureToken();
                    String hashedToken = hashToken(plainToken);
                    
                    RefreshToken refreshToken = RefreshToken.builder()
                            .id(idService.nextId())
                            .userId(userId)
                            .token(hashedToken)
                            .expiresAt(LocalDateTime.now().plusSeconds(refreshTokenExpirationMs / 1000))
                            .createdAt(LocalDateTime.now())
                            .revoked(false)
                            .ipAddress(ipAddress)
                            .userAgent(userAgent != null ? userAgent.substring(0, Math.min(userAgent.length(), 500)) : null)
                            .deviceName(parseDeviceName(userAgent))
                            .lastUsedAt(LocalDateTime.now())
                            .build();

                    return refreshTokenRepository.save(refreshToken)
                            .map(saved -> {
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
        return verifyAndRotate(token, null, null);
    }

    @Transactional
    public Mono<RefreshToken> verifyAndRotate(String token, String ipAddress, String userAgent) {
        String hashedToken = hashToken(token);
        return refreshTokenRepository.findByToken(hashedToken)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.unauthorized")))
                .flatMap(refreshToken -> {
                    if (refreshToken.isRevoked()) {
                        log.warn("Attempted reuse of revoked refresh token for user: {}", refreshToken.getUserId());
                        return auditService.logAction("REFRESH_TOKEN_REUSE", "USER",
                                        refreshToken.getUserId().toString(), refreshToken.getUserId(), null,
                                        "Possible token theft: revoked refresh token reused")
                                .then(refreshTokenRepository.revokeAllByUserId(refreshToken.getUserId()))
                                .then(Mono.error(new SecurityException("error.unauthorized")));
                    }

                    if (refreshToken.isExpired()) {
                        return Mono.error(new SecurityException("error.unauthorized"));
                    }

                    refreshToken.setRevoked(true);
                    return refreshTokenRepository.save(refreshToken)
                            .then(createRefreshToken(refreshToken.getUserId(), ipAddress, userAgent));
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

    public reactor.core.publisher.Flux<RefreshToken> getActiveSessions(Long userId) {
        return refreshTokenRepository.findActiveByUserId(userId);
    }

    @Transactional
    public Mono<Void> revokeTokenById(Long tokenId, Long userId) {
        return refreshTokenRepository.findById(tokenId)
                .filter(t -> t.getUserId().equals(userId) && !t.isRevoked())
                .flatMap(t -> {
                    t.setRevoked(true);
                    return refreshTokenRepository.save(t);
                })
                .then();
    }

    @Transactional
    public Mono<Void> revokeAllExceptCurrent(Long userId, String currentToken) {
        String currentHash = hashToken(currentToken);
        return refreshTokenRepository.revokeAllByUserIdExcept(userId, currentHash);
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

    static String parseDeviceName(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown";
        String browser = "Unknown Browser";
        String os = "Unknown OS";

        if (userAgent.contains("Edg/")) browser = "Edge";
        else if (userAgent.contains("Chrome/") && !userAgent.contains("Chromium/")) browser = "Chrome";
        else if (userAgent.contains("Firefox/")) browser = "Firefox";
        else if (userAgent.contains("Safari/") && !userAgent.contains("Chrome/")) browser = "Safari";
        else if (userAgent.contains("OPR/") || userAgent.contains("Opera/")) browser = "Opera";

        if (userAgent.contains("Windows")) os = "Windows";
        else if (userAgent.contains("Macintosh") || userAgent.contains("Mac OS")) os = "macOS";
        else if (userAgent.contains("Linux") && !userAgent.contains("Android")) os = "Linux";
        else if (userAgent.contains("Android")) os = "Android";
        else if (userAgent.contains("iPhone") || userAgent.contains("iPad")) os = "iOS";

        return browser + " on " + os;
    }
}
