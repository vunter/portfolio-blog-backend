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
     * Initiate email OTP setup: create an unverified config and send a verification code.
     */
    public Mono<Void> initSetup(Long userId) {
        var now = LocalDateTime.now();
        return mfaConfigRepository.deleteByUserIdAndMethod(userId, "EMAIL")
                .then(Mono.defer(() -> {
                    var config = UserMfaConfig.builder()
                            .id(idService.nextId())
                            .userId(userId)
                            .method("EMAIL")
                            .secretEncrypted(null)
                            .verified(false)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                    return mfaConfigRepository.save(config);
                }))
                .then(sendOtp(userId))
                .doOnSuccess(_ -> log.info("Email OTP setup initiated for user {}", userId));
    }

    /**
     * Verify the email OTP setup code: mark config as verified and enable MFA on the user.
     */
    public Mono<Void> verifySetup(Long userId, String code) {
        return verifyOtp(userId, code)
                .flatMap(valid -> {
                    if (!valid) {
                        return Mono.error(new IllegalArgumentException("Invalid verification code"));
                    }
                    return mfaConfigRepository.findByUserIdAndMethod(userId, "EMAIL")
                            .switchIfEmpty(Mono.error(new IllegalStateException("No pending email OTP setup")))
                            .flatMap(config -> {
                                config.setVerified(true);
                                config.setUpdatedAt(LocalDateTime.now());
                                config.setNewRecord(false);
                                return mfaConfigRepository.save(config);
                            })
                            .then(userRepository.findById(userId))
                            .flatMap(user -> {
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
                            .doOnSuccess(_ -> log.info("Email OTP verified and enabled for user {}", userId))
                            .then();
                });
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
