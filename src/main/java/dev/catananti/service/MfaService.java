package dev.catananti.service;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import dev.catananti.dto.MfaSetupResponse;
import dev.catananti.dto.MfaStatusResponse;
import dev.catananti.entity.UserMfaConfig;
import dev.catananti.repository.UserMfaConfigRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.security.AesEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Service for TOTP-based Multi-Factor Authentication.
 * Generates TOTP secrets, QR codes, and verifies OTP codes.
 */
@Service
@Slf4j
public class MfaService {

    private final UserMfaConfigRepository mfaConfigRepository;
    private final UserRepository userRepository;
    private final AesEncryptor aesEncryptor;
    private final IdService idService;

    private final String issuer;
    private final int digits;
    private final int periodSeconds;

    public MfaService(UserMfaConfigRepository mfaConfigRepository,
                      UserRepository userRepository,
                      AesEncryptor aesEncryptor,
                      IdService idService,
                      @Value("${mfa.totp.issuer:Catananti Portfolio}") String issuer,
                      @Value("${mfa.totp.digits:6}") int digits,
                      @Value("${mfa.totp.period-seconds:30}") int periodSeconds) {
        this.mfaConfigRepository = mfaConfigRepository;
        this.userRepository = userRepository;
        this.aesEncryptor = aesEncryptor;
        this.idService = idService;
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
                                        .secretKey(rawSecret)
                                        .method("TOTP")
                                        .build());
                    });
        });
    }

    /**
     * Verify setup: User provides the first TOTP code to confirm their authenticator is working.
     * If valid, mark the config as verified and enable MFA on the user.
     */
    public Mono<Boolean> verifySetup(Long userId, String code) {
        return mfaConfigRepository.findByUserIdAndMethod(userId, "TOTP")
                .flatMap(config -> {
                    if (config.getVerified()) {
                        return Mono.just(true); // already verified
                    }
                    String rawSecret = aesEncryptor.decrypt(config.getSecretEncrypted());
                    return verifyTotpCode(rawSecret, code)
                            .flatMap(valid -> {
                                if (!valid) return Mono.just(false);

                                config.setVerified(true);
                                config.setUpdatedAt(LocalDateTime.now());
                                config.setNewRecord(false);

                                return mfaConfigRepository.save(config)
                                        .then(enableMfaOnUser(userId, "TOTP"))
                                        .thenReturn(true);
                            });
                })
                .defaultIfEmpty(false);
    }

    /**
     * Verify a TOTP code during login (MFA challenge).
     */
    public Mono<Boolean> verifyTotp(Long userId, String code) {
        return mfaConfigRepository.findByUserIdAndMethod(userId, "TOTP")
                .filter(UserMfaConfig::getVerified)
                .flatMap(config -> {
                    String rawSecret = aesEncryptor.decrypt(config.getSecretEncrypted());
                    return verifyTotpCode(rawSecret, code);
                })
                .defaultIfEmpty(false);
    }

    /**
     * Disable MFA for a user: remove all MFA configs and update the user entity.
     */
    public Mono<Void> disableMfa(Long userId) {
        return mfaConfigRepository.deleteByUserId(userId)
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
     * Get MFA status for a user.
     */
    public Mono<MfaStatusResponse> getStatus(Long userId) {
        return mfaConfigRepository.findByUserId(userId)
                .filter(UserMfaConfig::getVerified)
                .map(UserMfaConfig::getMethod)
                .collectList()
                .flatMap(methods ->
                    userRepository.findById(userId)
                            .map(user -> MfaStatusResponse.builder()
                                    .mfaEnabled(Boolean.TRUE.equals(user.getMfaEnabled()))
                                    .methods(methods)
                                    .preferredMethod(user.getMfaPreferredMethod())
                                    .build())
                )
                .defaultIfEmpty(MfaStatusResponse.builder()
                        .mfaEnabled(false)
                        .methods(List.of())
                        .build());
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
                    if (expected.equals(code)) return true;
                }
                return false;
            } catch (Exception e) {
                log.error("TOTP verification failed: {}", e.getMessage());
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
