package dev.catananti.service;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import dev.catananti.dto.MfaSetupResponse;
import dev.catananti.dto.MfaStatusResponse;
import dev.catananti.entity.UserMfaConfig;
import dev.catananti.entity.MfaBackupCode;
import dev.catananti.repository.UserMfaConfigRepository;
import dev.catananti.repository.MfaBackupCodeRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.security.AesEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Service for TOTP-based Multi-Factor Authentication.
 * Generates TOTP secrets, QR codes, and verifies OTP codes.
 */
@Service
@Slf4j
public class MfaService {

    private final UserMfaConfigRepository mfaConfigRepository;
    private final MfaBackupCodeRepository backupCodeRepository;
    private final UserRepository userRepository;
    private final AesEncryptor aesEncryptor;
    private final IdService idService;
    private final EmailOtpService emailOtpService;
    private final ReactiveStringRedisTemplate redisTemplate;

    private final String issuer;
    private final int digits;
    private final int periodSeconds;
    private static final int BACKUP_CODE_COUNT = 10;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String TOTP_USED_PREFIX = "mfa:totp-used:";
    private static final String TOTP_ATTEMPTS_PREFIX = "mfa:totp-attempts:";
    private static final int MAX_TOTP_ATTEMPTS = 5;
    private static final Duration TOTP_ATTEMPTS_WINDOW = Duration.ofMinutes(5);

    public MfaService(UserMfaConfigRepository mfaConfigRepository,
                      MfaBackupCodeRepository backupCodeRepository,
                      UserRepository userRepository,
                      AesEncryptor aesEncryptor,
                      IdService idService,
                      EmailOtpService emailOtpService,
                      ReactiveStringRedisTemplate redisTemplate,
                      @Value("${mfa.totp.issuer:Catananti Portfolio}") String issuer,
                      @Value("${mfa.totp.digits:6}") int digits,
                      @Value("${mfa.totp.period-seconds:30}") int periodSeconds) {
        this.mfaConfigRepository = mfaConfigRepository;
        this.backupCodeRepository = backupCodeRepository;
        this.userRepository = userRepository;
        this.aesEncryptor = aesEncryptor;
        this.idService = idService;
        this.emailOtpService = emailOtpService;
        this.redisTemplate = redisTemplate;
        this.issuer = issuer;
        this.digits = digits;
        this.periodSeconds = periodSeconds;
    }

    /**
     * Initiate TOTP setup: Generate a new secret, produce a QR code data URI plus text key.
     * The config is saved as unverified until the user confirms with a valid code.
     */
    public Mono<MfaSetupResponse> setupTotp(Long userId, String userEmail) {
        return Mono.fromCallable(() -> {
            // Generate HMAC-SHA1 160-bit secret (standard for TOTP)
            KeyGenerator keyGen = KeyGenerator.getInstance("HmacSHA1");
            keyGen.init(160);
            SecretKey key = keyGen.generateKey();
            return Base64.getEncoder().encodeToString(key.getEncoded());
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(rawSecret -> {
            String encrypted = aesEncryptor.encrypt(rawSecret);

            // Build otpauth URI for QR code
            String otpauthUri = buildOtpauthUri(userEmail, rawSecret);

            // Convert to Base32 for display — authenticator apps expect Base32
            byte[] rawBytes = Base64.getDecoder().decode(rawSecret);
            String base32Secret = base32Encode(rawBytes);

            return Mono.fromCallable(() -> generateQrCodeDataUri(otpauthUri, 250, 250))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(qrDataUri -> {
                        // Delete any existing unverified TOTP config, then save the new one
                        var now = LocalDateTime.now();
                        var config = UserMfaConfig.builder()
                                .id(idService.nextId())
                                .userId(userId)
                                .method("TOTP")
                                .secretEncrypted(encrypted)
                                .verified(false)
                                .createdAt(now)
                                .updatedAt(now)
                                .build();

                        return mfaConfigRepository.deleteByUserIdAndMethod(userId, "TOTP")
                                .then(mfaConfigRepository.save(config))
                                .thenReturn(MfaSetupResponse.builder()
                                        .qrCodeDataUri(qrDataUri)
                                        .secretKey(base32Secret)
                                        .method("TOTP")
                                        .build());
                    });
        });
    }

    /**
     * Verify setup: User provides the first TOTP code to confirm their authenticator is working.
     * If valid, mark the config as verified, enable MFA, and generate backup codes.
     * Returns the plain-text backup codes on success, or empty list on failure.
     */
    public Mono<List<String>> verifySetup(Long userId, String code) {
        return mfaConfigRepository.findByUserIdAndMethod(userId, "TOTP")
                .flatMap(config -> {
                    if (config.getVerified()) {
                        return Mono.just(List.<String>of());
                    }
                    String rawSecret = aesEncryptor.decrypt(config.getSecretEncrypted());
                    return verifyTotpCode(rawSecret, code)
                            .flatMap(valid -> {
                                if (!valid) return Mono.just(List.<String>of());

                                config.setVerified(true);
                                config.setUpdatedAt(LocalDateTime.now());
                                config.setNewRecord(false);

                                return mfaConfigRepository.save(config)
                                        .then(enableMfaOnUser(userId, "TOTP"))
                                        .then(generateBackupCodes(userId));
                            });
                })
                .defaultIfEmpty(List.of());
    }

    /**
     * Verify a TOTP code during login (MFA challenge).
     * Includes brute force protection (5 attempts per 5 min) and code reuse prevention.
     */
    public Mono<Boolean> verifyTotp(Long userId, String code) {
        String attemptsKey = TOTP_ATTEMPTS_PREFIX + userId;
        return redisTemplate.opsForValue().increment(attemptsKey)
                .flatMap(attempts -> {
                    if (attempts == 1) {
                        return redisTemplate.expire(attemptsKey, TOTP_ATTEMPTS_WINDOW).thenReturn(attempts);
                    }
                    return Mono.just(attempts);
                })
                .flatMap(attempts -> {
                    if (attempts > MAX_TOTP_ATTEMPTS) {
                        log.warn("TOTP brute force limit reached for userId={}", userId);
                        return Mono.just(false);
                    }
                    return mfaConfigRepository.findByUserIdAndMethod(userId, "TOTP")
                            .filter(UserMfaConfig::getVerified)
                            .flatMap(config -> {
                                String rawSecret = aesEncryptor.decrypt(config.getSecretEncrypted());
                                return verifyTotpCode(rawSecret, code)
                                        .flatMap(valid -> {
                                            if (!valid) return Mono.just(false);
                                            // Prevent code reuse within the same window
                                            String usedKey = TOTP_USED_PREFIX + userId + ":" + code;
                                            return redisTemplate.opsForValue().setIfAbsent(usedKey, "1", Duration.ofSeconds(periodSeconds * 2L))
                                                    .flatMap(wasAbsent -> {
                                                        if (Boolean.FALSE.equals(wasAbsent)) {
                                                            log.warn("TOTP code reuse detected for userId={}", userId);
                                                            return Mono.just(false);
                                                        }
                                                        // Clear attempt counter on success
                                                        return redisTemplate.delete(attemptsKey).thenReturn(true);
                                                    });
                                        });
                            })
                            .defaultIfEmpty(false);
                })
                .onErrorResume(e -> {
                    log.warn("Redis unavailable for TOTP rate limiting, falling back to verify-only: {}", e.getMessage());
                    return mfaConfigRepository.findByUserIdAndMethod(userId, "TOTP")
                            .filter(UserMfaConfig::getVerified)
                            .flatMap(config -> {
                                String rawSecret = aesEncryptor.decrypt(config.getSecretEncrypted());
                                return verifyTotpCode(rawSecret, code);
                            })
                            .defaultIfEmpty(false);
                });
    }

    /**
     * Disable MFA for a user: remove all MFA configs and update the user entity.
     */
    public Mono<Void> disableMfa(Long userId) {
        return mfaConfigRepository.deleteByUserId(userId)
                .then(backupCodeRepository.deleteByUserId(userId))
                .then(userRepository.findById(userId))
                .flatMap(user -> {
                    user.setMfaEnabled(false);
                    user.setMfaPreferredMethod(null);
                    user.setUpdatedAt(LocalDateTime.now());
                    user.setNewRecord(false);
                    return userRepository.save(user);
                })
                .doOnSuccess(_ -> log.info("MFA disabled for user {}", userId))
                .then();
    }

    /**
     * Disable a single MFA method for a user after OTP verification.
     * If no methods remain, fully disables MFA and deletes backup codes.
     */
    public Mono<Void> disableMethod(Long userId, String methodToDisable) {
        return mfaConfigRepository.deleteByUserIdAndMethod(userId, methodToDisable)
                .then(mfaConfigRepository.findByUserId(userId)
                        .filter(UserMfaConfig::getVerified)
                        .collectList())
                .flatMap(remaining -> {
                    if (remaining.isEmpty()) {
                        return backupCodeRepository.deleteByUserId(userId)
                                .then(userRepository.findById(userId))
                                .flatMap(user -> {
                                    user.setMfaEnabled(false);
                                    user.setMfaPreferredMethod(null);
                                    user.setUpdatedAt(LocalDateTime.now());
                                    user.setNewRecord(false);
                                    return userRepository.save(user);
                                })
                                .then();
                    }
                    return userRepository.findById(userId)
                            .flatMap(user -> {
                                if (methodToDisable.equals(user.getMfaPreferredMethod())) {
                                    user.setMfaPreferredMethod(remaining.getFirst().getMethod());
                                    user.setUpdatedAt(LocalDateTime.now());
                                    user.setNewRecord(false);
                                    return userRepository.save(user);
                                }
                                return Mono.just(user);
                            })
                            .then();
                })
                .doOnSuccess(_ -> log.info("MFA method {} disabled for user {}", methodToDisable, userId));
    }

    /**
     * Verify an OTP code against any active MFA method for the user.
     * Returns true if the code is valid for any method (TOTP or EMAIL).
     */
    public Mono<Boolean> verifyAnyCode(Long userId, String code) {
        return mfaConfigRepository.findByUserId(userId)
                .filter(UserMfaConfig::getVerified)
                .collectList()
                .flatMap(configs -> {
                    Mono<Boolean> result = Mono.just(false);
                    for (UserMfaConfig config : configs) {
                        if ("TOTP".equals(config.getMethod()) && config.getSecretEncrypted() != null) {
                            String decryptedSecret = aesEncryptor.decrypt(config.getSecretEncrypted());
                            result = result.flatMap(valid -> valid ? Mono.just(true)
                                    : verifyTotpCode(decryptedSecret, code));
                        }
                    }
                    // Also try EMAIL OTP via Redis
                    boolean hasEmail = configs.stream().anyMatch(c -> "EMAIL".equals(c.getMethod()));
                    if (hasEmail) {
                        result = result.flatMap(valid -> valid ? Mono.just(true)
                                : emailOtpService.verifyOtp(userId, code));
                    }
                    return result;
                });
    }

    /**
     * Get MFA status for a user.
     */
    public Mono<MfaStatusResponse> getStatus(Long userId) {
        return mfaConfigRepository.findByUserId(userId)
                .filter(UserMfaConfig::getVerified)
                .map(UserMfaConfig::getMethod)
                .collectList()
                .flatMap(methods ->
                    Mono.zip(
                        userRepository.findById(userId),
                        backupCodeRepository.countByUserIdAndUsedFalse(userId).defaultIfEmpty(0L)
                    ).map(tuple -> MfaStatusResponse.builder()
                            .mfaEnabled(Boolean.TRUE.equals(tuple.getT1().getMfaEnabled()))
                            .methods(methods)
                            .preferredMethod(tuple.getT1().getMfaPreferredMethod())
                            .backupCodesRemaining(tuple.getT2())
                            .build())
                )
                .defaultIfEmpty(MfaStatusResponse.builder()
                        .mfaEnabled(false)
                        .methods(List.of())
                        .build());
    }

    // ==================== Backup Codes ====================

    /**
     * Generate a new set of backup codes for a user (replaces any existing ones).
     * Returns the plain-text codes (shown once to the user).
     */
    public Mono<List<String>> generateBackupCodes(Long userId) {
        return backupCodeRepository.deleteByUserId(userId)
                .then(Mono.fromCallable(() -> {
                    List<String> plainCodes = new ArrayList<>();
                    List<MfaBackupCode> entities = new ArrayList<>();
                    LocalDateTime now = LocalDateTime.now();

                    for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
                        String code = generateRandomCode();
                        plainCodes.add(code);
                        entities.add(MfaBackupCode.builder()
                                .id(idService.nextId())
                                .userId(userId)
                                .codeHash(hashCode(code))
                                .used(false)
                                .createdAt(now)
                                .build());
                    }

                    return Map.entry(plainCodes, entities);
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(pair -> Flux.fromIterable(pair.getValue())
                        .flatMap(backupCodeRepository::save)
                        .collectList()
                        .thenReturn(pair.getKey()));
    }

    /**
     * Verify a backup code during MFA login. Marks the code as used if valid.
     */
    public Mono<Boolean> verifyBackupCode(Long userId, String code) {
        String hash = hashCode(code.trim().replace("-", "").replace(" ", ""));
        return backupCodeRepository.findByUserIdAndUsedFalse(userId)
                .filter(bc -> bc.getCodeHash().equals(hash))
                .next()
                .flatMap(bc -> {
                    bc.setUsed(true);
                    bc.setUsedAt(LocalDateTime.now());
                    bc.setNewRecord(false);
                    return backupCodeRepository.save(bc).thenReturn(true);
                })
                .defaultIfEmpty(false);
    }

    /**
     * Get the count of remaining unused backup codes.
     */
    public Mono<Long> getRemainingBackupCodeCount(Long userId) {
        return backupCodeRepository.countByUserIdAndUsedFalse(userId);
    }

    private static String generateRandomCode() {
        // 8-digit alphanumeric code formatted as XXXX-XXXX
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return sb.substring(0, 4) + "-" + sb.substring(4, 8);
    }

    private static String hashCode(String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(code.toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash backup code", e);
        }
    }

    // ==================== Private Helpers ====================

    private Mono<Void> enableMfaOnUser(Long userId, String method) {
        return userRepository.findById(userId)
                .flatMap(user -> {
                    user.setMfaEnabled(true);
                    user.setMfaPreferredMethod(method);
                    user.setUpdatedAt(LocalDateTime.now());
                    user.setNewRecord(false);
                    return userRepository.save(user);
                })
                .doOnSuccess(_ -> log.info("MFA enabled for user {} with method {}", userId, method))
                .then();
    }

    private Mono<Boolean> verifyTotpCode(String base64Secret, String code) {
        return Mono.fromCallable(() -> {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(base64Secret);
                SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA1");
                var generator = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(periodSeconds), digits);

                Instant now = Instant.now();
                // Check current window and ±1 window for clock drift tolerance
                for (int drift = -1; drift <= 1; drift++) {
                    Instant adjusted = now.plusSeconds((long) drift * periodSeconds);
                    String expected = String.format("%0" + digits + "d", generator.generateOneTimePassword(key, adjusted));
                    if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) return true;
                }
                return false;
            } catch (Exception e) {
                log.error("TOTP verification failed", e);
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildOtpauthUri(String account, String base64Secret) {
        // Convert Base64 secret into Base32 for otpauth URI (standard format)
        byte[] raw = Base64.getDecoder().decode(base64Secret);
        String base32Secret = base32Encode(raw);

        return "otpauth://totp/"
                + URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8)
                + "?secret=" + base32Secret
                + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8)
                + "&digits=" + digits
                + "&period=" + periodSeconds;
    }

    private String generateQrCodeDataUri(String text, int width, int height) throws Exception {
        QRCodeWriter qrWriter = new QRCodeWriter();
        BitMatrix matrix = qrWriter.encode(text, BarcodeFormat.QR_CODE, width, height);
        ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", pngOutputStream);
        String base64 = Base64.getEncoder().encodeToString(pngOutputStream.toByteArray());
        return "data:image/png;base64," + base64;
    }

    /** RFC 4648 Base32 encoding (no padding). */
    private static String base32Encode(byte[] data) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(alphabet.charAt((buffer >> (bitsLeft - 5)) & 31));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(alphabet.charAt((buffer << (5 - bitsLeft)) & 31));
        }
        return sb.toString();
    }
}
