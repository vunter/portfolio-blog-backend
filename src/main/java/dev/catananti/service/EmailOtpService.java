package dev.catananti.service;

import dev.catananti.entity.UserMfaConfig;
import dev.catananti.repository.UserMfaConfigRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.security.AesEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
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
    private static final String OTP_EMAIL_RATE_PREFIX = "mfa:otp:email:";
    private static final String OTP_ATTEMPTS_PREFIX = "mfa:otp-attempts:";
    private final int maxOtpSendsPerEmail;
    private final Duration otpEmailRateWindow;
    private final int maxOtpVerifyAttempts;
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
                           @Value("${mfa.email-otp.expiration-minutes:10}") int expirationMinutes,
                           @Value("${mfa.email-otp.max-sends:3}") int maxOtpSendsPerEmail,
                           @Value("${mfa.email-otp.rate-window-minutes:15}") int otpEmailRateWindowMinutes,
                           @Value("${mfa.email-otp.max-verify-attempts:5}") int maxOtpVerifyAttempts) {
        this.redisTemplate = redisTemplate;
        this.emailService = emailService;
        this.mfaConfigRepository = mfaConfigRepository;
        this.userRepository = userRepository;
        this.idService = idService;
        this.otpLength = otpLength;
        this.expirationMinutes = expirationMinutes;
        this.maxOtpSendsPerEmail = maxOtpSendsPerEmail;
        this.otpEmailRateWindow = Duration.ofMinutes(otpEmailRateWindowMinutes);
        this.maxOtpVerifyAttempts = maxOtpVerifyAttempts;
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
                        return Mono.error(new IllegalArgumentException("error.invalid_verification_code"));
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
     *
     * Per-email rate limit: max 3 OTP sends per 15 minutes.
     * OTP reuse: if an OTP already exists for this user and hasn't expired,
     * the same OTP is resent instead of generating a new one (prevents
     * an attacker from invalidating a legitimate user's OTP).
     */
    public Mono<Void> sendOtp(Long userId) {
        return userRepository.findById(userId)
                .flatMap(user -> {
                    String emailRateKey = OTP_EMAIL_RATE_PREFIX + user.getEmail().toLowerCase();

                    // Check per-email rate limit
                    return redisTemplate.opsForValue().increment(emailRateKey)
                            .flatMap(count -> {
                                if (count == 1) {
                                    return redisTemplate.expire(emailRateKey, otpEmailRateWindow).thenReturn(count);
                                }
                                return Mono.just(count);
                            })
                            .flatMap(count -> {
                                if (count > maxOtpSendsPerEmail) {
                                    log.warn("OTP email rate limit exceeded for user {}", userId);
                                    return Mono.error(new ResponseStatusException(
                                            HttpStatus.TOO_MANY_REQUESTS,
                                            "error.otp_rate_limit"));
                                }

                                String redisKey = REDIS_PREFIX + userId;

                                // Reuse existing OTP if one is still valid
                                return redisTemplate.opsForValue().get(redisKey)
                                        .flatMap(existingOtp -> {
                                            log.debug("Reusing existing OTP for user {}", userId);
                                            return emailService.sendOtpVerification(
                                                    user.getEmail(),
                                                    user.getName(),
                                                    existingOtp,
                                                    expirationMinutes)
                                                    .doOnSuccess(_ -> log.debug("Email OTP resent to user {}", userId))
                                                    .thenReturn(existingOtp);
                                        })
                                        .switchIfEmpty(Mono.defer(() -> {
                                            // No existing OTP — generate a new one
                                            String otp = generateOtp();
                                            return redisTemplate.opsForValue()
                                                    .set(redisKey, otp, Duration.ofMinutes(expirationMinutes))
                                                    .then(emailService.sendOtpVerification(
                                                            user.getEmail(),
                                                            user.getName(),
                                                            otp,
                                                            expirationMinutes))
                                                    .thenReturn(otp);
                                        }))
                                        .then();
                            });
                });
    }

    /**
     * Verify the OTP code provided by the user during login.
     *
     * SEG-5: Brute-force protection. Each verify attempt increments a counter keyed by
     * userId (TTL = OTP validity, set on the first increment). Once the counter reaches
     * the configured threshold, the OTP Redis key is invalidated (deleted) and the attempt
     * is rejected, so a static 6-digit code cannot be guessed across repeated logins.
     * On success both the OTP key and the attempt counter are removed.
     */
    public Mono<Boolean> verifyOtp(Long userId, String code) {
        String redisKey = REDIS_PREFIX + userId;
        String attemptsKey = OTP_ATTEMPTS_PREFIX + userId;
        return redisTemplate.opsForValue().increment(attemptsKey)
                .flatMap(attempts -> {
                    Mono<Boolean> ttlSet = attempts == 1
                            ? redisTemplate.expire(attemptsKey, Duration.ofMinutes(expirationMinutes))
                            : Mono.just(true);
                    return ttlSet.then(Mono.defer(() -> {
                        if (attempts > maxOtpVerifyAttempts) {
                            // Too many guesses — invalidate the OTP so a fresh login cannot keep guessing it.
                            log.warn("Email OTP brute force limit reached for userId={}", userId);
                            return redisTemplate.delete(redisKey).thenReturn(false);
                        }
                        return redisTemplate.opsForValue().get(redisKey)
                                .flatMap(storedOtp -> {
                                    if (java.security.MessageDigest.isEqual(
                                            storedOtp.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                            code.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                                        // Delete OTP and attempt counter after successful verification (one-time use)
                                        return redisTemplate.delete(redisKey)
                                                .then(redisTemplate.delete(attemptsKey))
                                                .thenReturn(true);
                                    }
                                    return Mono.just(false);
                                })
                                .defaultIfEmpty(false);
                    }));
                });
    }

    private String generateOtp() {
        int max = (int) Math.pow(10, otpLength);
        int otp = secureRandom.nextInt(max);
        return String.format("%0" + otpLength + "d", otp);
    }
}
