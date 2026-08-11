package dev.catananti.service;

import dev.catananti.entity.User;
import dev.catananti.entity.UserMfaConfig;
import dev.catananti.repository.UserMfaConfigRepository;
import dev.catananti.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailOtpServiceTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @Mock
    private EmailService emailService;

    @Mock
    private UserMfaConfigRepository mfaConfigRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private IdService idService;

    @Mock
    private org.springframework.transaction.reactive.TransactionalOperator transactionalOperator;

    private EmailOtpService emailOtpService;

    private User testUser;

    @BeforeEach
    void setUp() {
        emailOtpService = new EmailOtpService(
                redisTemplate, emailService, mfaConfigRepository,
                userRepository, idService, transactionalOperator,
                6,   // otpLength
                10,  // expirationMinutes
                3,   // maxOtpSendsPerEmail
                15,  // otpEmailRateWindowMinutes
                5    // maxOtpVerifyAttempts
        );

        testUser = User.builder()
                .id(100L)
                .email("user@example.com")
                .name("Test User")
                .role("VIEWER")
                .mfaEnabled(false)
                .mfaPreferredMethod(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // SEG-5: brute-force attempt counter. verifyOtp (and verifySetup, which calls it)
        // increments a per-user counter, sets a TTL on first increment, and on success
        // deletes both the OTP key and the attempt-counter key. Stub these leniently so the
        // happy-path (attempts == 1, under the threshold of 5) and negative paths all work.
        lenient().when(valueOperations.increment("mfa:otp-attempts:100")).thenReturn(Mono.just(1L));
        lenient().when(redisTemplate.expire(eq("mfa:otp-attempts:100"), any(Duration.class)))
                .thenReturn(Mono.just(true));
        lenient().when(redisTemplate.delete("mfa:otp-attempts:100")).thenReturn(Mono.just(1L));
    }

    // ==================== verifyOtp ====================

    @Test
    @DisplayName("verifyOtp should return true when code matches stored OTP")
    void verifyOtp_ShouldReturnTrue_WhenCodeMatches() {
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.just("123456"));
        when(redisTemplate.delete("mfa:email-otp:100")).thenReturn(Mono.just(1L));

        StepVerifier.create(emailOtpService.verifyOtp(100L, "123456"))
                .assertNext(result -> assertThat(result).isTrue())
                .verifyComplete();

        verify(redisTemplate).delete("mfa:email-otp:100");
    }

    @Test
    @DisplayName("verifyOtp should return false when OTP was concurrently consumed (DEL count 0)")
    void verifyOtp_ShouldReturnFalse_WhenOtpConcurrentlyConsumed() {
        // Both requests read the same OTP, but Redis DEL is atomic: the loser sees 0 keys deleted
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.just("123456"));
        when(redisTemplate.delete("mfa:email-otp:100")).thenReturn(Mono.just(0L));

        StepVerifier.create(emailOtpService.verifyOtp(100L, "123456"))
                .assertNext(result -> assertThat(result).isFalse())
                .verifyComplete();
    }

    @Test
    @DisplayName("verifyOtp should return false when code does not match")
    void verifyOtp_ShouldReturnFalse_WhenCodeDoesNotMatch() {
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.just("123456"));

        StepVerifier.create(emailOtpService.verifyOtp(100L, "999999"))
                .assertNext(result -> assertThat(result).isFalse())
                .verifyComplete();

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("verifyOtp should return false when no OTP stored (expired or never sent)")
    void verifyOtp_ShouldReturnFalse_WhenNoOtpStored() {
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.empty());

        StepVerifier.create(emailOtpService.verifyOtp(100L, "123456"))
                .assertNext(result -> assertThat(result).isFalse())
                .verifyComplete();
    }

    // ==================== sendOtp ====================

    @Test
    @DisplayName("sendOtp should generate new OTP and send email when no existing OTP")
    void sendOtp_ShouldGenerateAndSendNewOtp_WhenNoExistingOtp() {
        when(userRepository.findById(100L)).thenReturn(Mono.just(testUser));
        when(valueOperations.increment("mfa:otp:email:user@example.com")).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(eq("mfa:otp:email:user@example.com"), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.empty());
        when(valueOperations.set(eq("mfa:email-otp:100"), anyString(), eq(Duration.ofMinutes(10))))
                .thenReturn(Mono.just(true));
        when(emailService.sendOtpVerification(eq("user@example.com"), eq("Test User"), anyString(), eq(10)))
                .thenReturn(Mono.empty());

        StepVerifier.create(emailOtpService.sendOtp(100L))
                .verifyComplete();

        verify(valueOperations).set(eq("mfa:email-otp:100"), anyString(), eq(Duration.ofMinutes(10)));
        verify(emailService).sendOtpVerification(eq("user@example.com"), eq("Test User"), anyString(), eq(10));
    }

    @Test
    @DisplayName("sendOtp should resend existing OTP when one is still valid in Redis")
    void sendOtp_ShouldResendExistingOtp_WhenOtpExists() {
        when(userRepository.findById(100L)).thenReturn(Mono.just(testUser));
        when(valueOperations.increment("mfa:otp:email:user@example.com")).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(eq("mfa:otp:email:user@example.com"), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.just("654321"));
        when(emailService.sendOtpVerification("user@example.com", "Test User", "654321", 10))
                .thenReturn(Mono.empty());

        StepVerifier.create(emailOtpService.sendOtp(100L))
                .verifyComplete();

        // Should NOT generate a new OTP
        verify(valueOperations, never()).set(eq("mfa:email-otp:100"), anyString(), any(Duration.class));
        // Should resend the existing one
        verify(emailService).sendOtpVerification("user@example.com", "Test User", "654321", 10);
    }

    @Test
    @DisplayName("sendOtp should throw TOO_MANY_REQUESTS when rate limit exceeded")
    void sendOtp_ShouldThrow_WhenRateLimitExceeded() {
        when(userRepository.findById(100L)).thenReturn(Mono.just(testUser));
        when(valueOperations.increment("mfa:otp:email:user@example.com")).thenReturn(Mono.just(4L));

        StepVerifier.create(emailOtpService.sendOtp(100L))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode())
                            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                })
                .verify();
    }

    @Test
    @DisplayName("sendOtp should set expire on rate key for first request only")
    void sendOtp_ShouldSetExpire_OnFirstRequestOnly() {
        when(userRepository.findById(100L)).thenReturn(Mono.just(testUser));
        when(valueOperations.increment("mfa:otp:email:user@example.com")).thenReturn(Mono.just(2L));
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.empty());
        when(valueOperations.set(eq("mfa:email-otp:100"), anyString(), eq(Duration.ofMinutes(10))))
                .thenReturn(Mono.just(true));
        when(emailService.sendOtpVerification(eq("user@example.com"), eq("Test User"), anyString(), eq(10)))
                .thenReturn(Mono.empty());

        StepVerifier.create(emailOtpService.sendOtp(100L))
                .verifyComplete();

        // Count > 1 so expire should NOT be called
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    // ==================== initSetup ====================

    @Test
    @DisplayName("initSetup should delete existing config, save new one, and send OTP")
    void initSetup_ShouldCreateConfigAndSendOtp() {
        when(mfaConfigRepository.deleteByUserIdAndMethod(100L, "EMAIL")).thenReturn(Mono.empty());
        when(idService.nextId()).thenReturn(999L);
        when(mfaConfigRepository.save(any(UserMfaConfig.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        // sendOtp chain
        when(userRepository.findById(100L)).thenReturn(Mono.just(testUser));
        when(valueOperations.increment("mfa:otp:email:user@example.com")).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(eq("mfa:otp:email:user@example.com"), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.empty());
        when(valueOperations.set(eq("mfa:email-otp:100"), anyString(), eq(Duration.ofMinutes(10))))
                .thenReturn(Mono.just(true));
        when(emailService.sendOtpVerification(eq("user@example.com"), eq("Test User"), anyString(), eq(10)))
                .thenReturn(Mono.empty());

        StepVerifier.create(emailOtpService.initSetup(100L))
                .verifyComplete();

        verify(mfaConfigRepository).deleteByUserIdAndMethod(100L, "EMAIL");
        verify(mfaConfigRepository).save(argThat(config ->
                config.getUserId().equals(100L)
                        && "EMAIL".equals(config.getMethod())
                        && Boolean.FALSE.equals(config.getVerified())
                        && config.getSecretEncrypted() == null
        ));
    }

    // ==================== verifySetup ====================

    @Test
    @DisplayName("verifySetup should mark config as verified and enable MFA when code is correct")
    void verifySetup_ShouldEnableMfa_WhenCodeCorrect() {
        // verifyOtp returns true
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.just("123456"));
        when(redisTemplate.delete("mfa:email-otp:100")).thenReturn(Mono.just(1L));

        UserMfaConfig config = UserMfaConfig.builder()
                .id(999L).userId(100L).method("EMAIL").verified(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        when(mfaConfigRepository.findByUserIdAndMethod(100L, "EMAIL")).thenReturn(Mono.just(config));
        when(mfaConfigRepository.save(any(UserMfaConfig.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(userRepository.enableMfaWithFallbackPreferred(eq(100L), eq("EMAIL"), any(LocalDateTime.class)))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(emailOtpService.verifySetup(100L, "123456"))
                .verifyComplete();

        verify(mfaConfigRepository).save(argThat(c ->
                Boolean.TRUE.equals(c.getVerified()) && !c.isNewRecord()
        ));
        verify(userRepository).enableMfaWithFallbackPreferred(eq(100L), eq("EMAIL"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("verifySetup should throw when code is invalid")
    void verifySetup_ShouldThrow_WhenCodeInvalid() {
        // verifyOtp returns false (wrong code)
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.just("123456"));

        StepVerifier.create(emailOtpService.verifySetup(100L, "000000"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("verifySetup should throw when no pending MFA config found")
    void verifySetup_ShouldThrow_WhenNoPendingConfig() {
        // verifyOtp returns true
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.just("123456"));
        when(redisTemplate.delete("mfa:email-otp:100")).thenReturn(Mono.just(1L));

        when(mfaConfigRepository.findByUserIdAndMethod(100L, "EMAIL")).thenReturn(Mono.empty());
        // The partial update is eagerly evaluated in the .then() chain, so it must be stubbed
        // even though the switchIfEmpty error prevents it from being subscribed to
        lenient().when(userRepository.enableMfaWithFallbackPreferred(eq(100L), eq("EMAIL"), any(LocalDateTime.class)))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(emailOtpService.verifySetup(100L, "123456"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    @DisplayName("verifySetup should enable MFA passing EMAIL only as fallback preferred method")
    void verifySetup_ShouldNotOverwritePreferredMethod_WhenAlreadySet() {
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.just("123456"));
        when(redisTemplate.delete("mfa:email-otp:100")).thenReturn(Mono.just(1L));

        UserMfaConfig config = UserMfaConfig.builder()
                .id(999L).userId(100L).method("EMAIL").verified(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        when(mfaConfigRepository.findByUserIdAndMethod(100L, "EMAIL")).thenReturn(Mono.just(config));
        when(mfaConfigRepository.save(any(UserMfaConfig.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(userRepository.enableMfaWithFallbackPreferred(eq(100L), eq("EMAIL"), any(LocalDateTime.class)))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(emailOtpService.verifySetup(100L, "123456"))
                .verifyComplete();

        // An existing preferred method (e.g. TOTP) is preserved by the COALESCE in the
        // partial UPDATE — the service only supplies EMAIL as the fallback.
        verify(userRepository).enableMfaWithFallbackPreferred(eq(100L), eq("EMAIL"), any(LocalDateTime.class));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("verifySetup should throw when OTP is expired (no stored OTP)")
    void verifySetup_ShouldThrow_WhenOtpExpired() {
        when(valueOperations.get("mfa:email-otp:100")).thenReturn(Mono.empty());

        StepVerifier.create(emailOtpService.verifySetup(100L, "123456"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
