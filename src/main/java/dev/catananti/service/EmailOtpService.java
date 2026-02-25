package dev.catananti.service;

import dev.catananti.entity.UserMfaConfig;
import dev.catananti.repository.UserMfaConfigRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.security.AesEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Service for Email-based OTP as an MFA method.
 * Generates a numeric OTP, stores it in Redis with TTL, and sends via email.
 */
@Service
@Slf4j
public class EmailOtpService {

    private static final String REDIS_PREFIX = "mfa:email-otp:";
    private final SecureRandom secureRandom = new SecureRandom();

    private final ReactiveStringRedisTemplate redisTemplate;
    private final EmailService emailService;
    private final UserMfaConfigRepository mfaConfigRepository;
    private final UserRepository userRepository;
    private final IdService idService;

    private final int otpLength;
    private final int expirationMinutes;

    public EmailOtpService(ReactiveStringRedisTemplate redisTemplate,
                           EmailService emailService,
                           UserMfaConfigRepository mfaConfigRepository,
                           UserRepository userRepository,
                           IdService idService,
                           @Value("${mfa.email-otp.length:6}") int otpLength,
                           @Value("${mfa.email-otp.expiration-minutes:10}") int expirationMinutes) {
        this.redisTemplate = redisTemplate;
        this.emailService = emailService;
        this.mfaConfigRepository = mfaConfigRepository;
        this.userRepository = userRepository;
        this.idService = idService;
        this.otpLength = otpLength;
        this.expirationMinutes = expirationMinutes;
    }

    /**
     * Enable email OTP for a user.
     * Creates a verified MFA config entry (email OTP doesn't need separate setup verification).
     */
    public Mono<Void> enableEmailOtp(Long userId) {
        var now = LocalDateTime.now();
        return mfaConfigRepository.deleteByUserIdAndMethod(userId, "EMAIL")
                .then(Mono.defer(() -> {
                    var config = UserMfaConfig.builder()
                            .id(idService.nextId())
                            .userId(userId)
                            .method("EMAIL")
                            .secretEncrypted(null) // email OTP uses no persistent secret
                            .verified(true) // immediately verified — the email is already confirmed
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                    return mfaConfigRepository.save(config);
                }))
                .then(userRepository.findById(userId))
                .flatMap(user -> {
                    // Enable MFA on user if not already
                    if (!Boolean.TRUE.equals(user.getMfaEnabled())) {
                        user.setMfaEnabled(true);
                    }
                    if (user.getMfaPreferredMethod() == null) {
                        user.setMfaPreferredMethod("EMAIL");
                    }
                    user.setUpdatedAt(LocalDateTime.now());
                    user.setNewRecord(false);
                    return userRepository.save(user);
                })
                .doOnSuccess(_ -> log.info("Email OTP enabled for user {}", userId))
                .then();
    }

    /**
     * Generate and send an OTP code to the user's email.
     * Called during login when MFA is required.
     */
    public Mono<Void> sendOtp(Long userId) {
        return userRepository.findById(userId)
                .flatMap(user -> {
                    String otp = generateOtp();
                    String redisKey = REDIS_PREFIX + userId;

                    return redisTemplate.opsForValue()
                            .set(redisKey, otp, Duration.ofMinutes(expirationMinutes))
                            .then(emailService.sendOtpVerification(
                                    user.getEmail(),
                                    user.getName(),
                                    otp,
                                    expirationMinutes))
                            .doOnSuccess(_ -> log.debug("Email OTP sent to user {}", userId));
                });
    }

    /**
     * Verify the OTP code provided by the user during login.
     */
    public Mono<Boolean> verifyOtp(Long userId, String code) {
        String redisKey = REDIS_PREFIX + userId;
        return redisTemplate.opsForValue().get(redisKey)
                .flatMap(storedOtp -> {
                    if (storedOtp.equals(code)) {
                        // Delete OTP after successful verification (one-time use)
                        return redisTemplate.delete(redisKey)
                                .thenReturn(true);
                    }
                    return Mono.just(false);
                })
                .defaultIfEmpty(false);
    }

    private String generateOtp() {
        int max = (int) Math.pow(10, otpLength);
        int otp = secureRandom.nextInt(max);
        return String.format("%0" + otpLength + "d", otp);
    }
}
