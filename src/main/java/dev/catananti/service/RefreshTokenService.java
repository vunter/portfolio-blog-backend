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
import java.time.Duration;
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
    private final dev.catananti.scheduler.SchedulerLock schedulerLock;

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
        // Don't revoke other devices' tokens here — multi-device sessions are a feature, not a bug.
        // verifyAndRotate() handles per-token rotation safely; the explicit "sign out everywhere"
        // action is revokeAllUserTokens(), which users can trigger from the security settings page.
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
                })
                .doOnSuccess(rt -> log.info("Refresh token created for user: {}", userId));
    }

    public Mono<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByTokenAndRevokedFalse(hashToken(token));
    }

    @Transactional
    public Mono<RefreshToken> verifyAndRotate(String token) {
        return verifyAndRotate(token, null, null);
    }

    @Value("${jwt.refresh-rotation-grace-seconds:10}")
    private int rotationGracePeriodSeconds;

    @Transactional
    public Mono<RefreshToken> verifyAndRotate(String token, String ipAddress, String userAgent) {
        String hashedToken = hashToken(token);
        String hashPrefix = hashedToken.substring(0, Math.min(12, hashedToken.length()));

        // Revoke the token (returns row count), then fetch it.
        // Only one concurrent request can win the update; losers get 0 rows.
        return refreshTokenRepository.revokeByTokenIfActive(hashedToken)
                .filter(updated -> updated > 0)
                .flatMap(updated -> refreshTokenRepository.findByToken(hashedToken))
                .flatMap(refreshToken -> {
                    if (refreshToken.isExpired()) {
                        log.warn("Refresh attempt with expired token hash={}... for user={}", hashPrefix, refreshToken.getUserId());
                        return Mono.error(new SecurityException("error.unauthorized"));
                    }

                    log.info("Token rotation: revoked hash={}... for user={}", hashPrefix, refreshToken.getUserId());
                    return rotateRefreshToken(refreshToken, ipAddress, userAgent);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // Token was already revoked or doesn't exist — check for grace period reuse
                    return refreshTokenRepository.findByToken(hashedToken)
                            .flatMap(revokedToken -> {
                                if (revokedToken.isRevoked()) {
                                    log.warn("Refresh attempt with revoked token hash={}... for user={}", hashPrefix, revokedToken.getUserId());
                                    return handleRevokedTokenReuse(revokedToken, ipAddress, userAgent);
                                }
                                return Mono.error(new ResourceNotFoundException("error.unauthorized"));
                            })
                            .switchIfEmpty(Mono.error(new ResourceNotFoundException("error.unauthorized")));
                }));
    }

    /**
     * Handles reuse of a revoked refresh token with a grace period.
     * If a new token was recently issued (within grace period), this is likely
     * a retry after a failed rotation (e.g., deploy interrupted the response).
     * Re-rotate from the latest active token instead of treating it as theft.
     */
    private Mono<RefreshToken> handleRevokedTokenReuse(RefreshToken revokedToken, String ipAddress, String userAgent) {
        return refreshTokenRepository.findActiveByUserId(revokedToken.getUserId())
                .next()
                .flatMap(latestActive -> {
                    boolean withinGrace = latestActive.getCreatedAt()
                            .plusSeconds(rotationGracePeriodSeconds)
                            .isAfter(LocalDateTime.now());

                    if (withinGrace) {
                        log.info("Grace period: re-rotating for user {} (failed rotation retry)", revokedToken.getUserId());
                        latestActive.setRevoked(true);
                        return refreshTokenRepository.save(latestActive)
                                .then(rotateRefreshToken(latestActive, ipAddress, userAgent));
                    }

                    log.warn("Revoked token reuse outside grace period for user: {}", revokedToken.getUserId());
                    return auditService.logAction("REFRESH_TOKEN_REUSE", "USER",
                                    revokedToken.getUserId().toString(), revokedToken.getUserId(), null,
                                    "Possible token theft: revoked refresh token reused outside grace period")
                            .then(refreshTokenRepository.revokeAllByUserId(revokedToken.getUserId()))
                            .then(Mono.error(new SecurityException("error.unauthorized")));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Revoked token reuse with no active tokens for user: {}", revokedToken.getUserId());
                    return Mono.error(new SecurityException("error.unauthorized"));
                }));
    }

    /**
     * Creates a new refresh token for rotation without revoking all existing tokens.
     * Used during token rotation where only the specific old token is revoked.
     */
    private Mono<RefreshToken> rotateRefreshToken(RefreshToken oldToken, String ipAddress, String userAgent) {
        return Mono.defer(() -> {
            String plainToken = generateSecureToken();
            String hashedToken = hashToken(plainToken);

            RefreshToken newToken = RefreshToken.builder()
                    .id(idService.nextId())
                    .userId(oldToken.getUserId())
                    .token(hashedToken)
                    .expiresAt(LocalDateTime.now().plusSeconds(refreshTokenExpirationMs / 1000))
                    .createdAt(LocalDateTime.now())
                    .revoked(false)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent != null ? userAgent.substring(0, Math.min(userAgent.length(), 500)) : null)
                    .deviceName(parseDeviceName(userAgent))
                    .lastUsedAt(LocalDateTime.now())
                    .build();

            return refreshTokenRepository.save(newToken)
                    .map(saved -> {
                        saved.setToken(plainToken);
                        return saved;
                    });
        }).doOnSuccess(rt -> log.info("Refresh token rotated for user: {}", oldToken.getUserId()));
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

    @Scheduled(fixedRateString = "${scheduling.refresh-token-cleanup-ms:3600000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void cleanupExpiredTokens() {
        schedulerLock.executeWithLock("refresh-token-cleanup", Duration.ofMinutes(5),
                refreshTokenRepository.deleteExpired(LocalDateTime.now())
                        .timeout(Duration.ofSeconds(30))
                        .doOnSuccess(count -> log.info("Expired refresh tokens cleaned up"))
                        .doOnError(e -> log.error("Failed to cleanup expired refresh tokens", e))
                        .onErrorComplete()
                        .then()
        ).subscribe();
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
