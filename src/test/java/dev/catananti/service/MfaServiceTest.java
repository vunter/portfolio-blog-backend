package dev.catananti.service;

import dev.catananti.dto.MfaSetupResponse;
import dev.catananti.dto.MfaStatusResponse;
import dev.catananti.entity.MfaBackupCode;
import dev.catananti.entity.User;
import dev.catananti.entity.UserMfaConfig;
import dev.catananti.repository.MfaBackupCodeRepository;
import dev.catananti.repository.UserMfaConfigRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.security.AesEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    @Mock
    private UserMfaConfigRepository mfaConfigRepository;

    @Mock
    private MfaBackupCodeRepository backupCodeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AesEncryptor aesEncryptor;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private IdService idService;

    @Mock
    private EmailOtpService emailOtpService;

    @Mock
    private AuditService auditService;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    private MfaService mfaService;

    private static final Long USER_ID = 1234567890123456789L;
    private static final String USER_EMAIL = "test@example.com";
    private static final String ENCRYPTED_SECRET = "encrypted-secret-value";
    private static final String RAW_SECRET = "dGVzdC1zZWNyZXQta2V5LWJhc2U2NA=="; // valid base64

    private User testUser;

    @BeforeEach
    void setUp() {
        mfaService = new MfaService(
                mfaConfigRepository,
                backupCodeRepository,
                userRepository,
                aesEncryptor,
                passwordEncoder,
                idService,
                emailOtpService,
                auditService,
                redisTemplate,
                "Catananti Portfolio",  // issuer
                6,                       // digits
                30,                      // periodSeconds
                5,                       // maxTotpAttempts
                5                        // totpAttemptsWindowMinutes
        );

        testUser = User.builder()
                .id(USER_ID)
                .email(USER_EMAIL)
                .name("Test User")
                .passwordHash("$2a$10$hashedpassword")
                .role("ADMIN")
                .mfaEnabled(false)
                .mfaPreferredMethod(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ==================== setupTotp ====================

    @Nested
    @DisplayName("setupTotp")
    class SetupTotpTests {

        @Test
        @DisplayName("Should generate TOTP setup with QR code and secret key")
        void setupTotp_ShouldReturnQrCodeAndSecret() {
            when(aesEncryptor.encrypt(anyString())).thenReturn(ENCRYPTED_SECRET);
            when(idService.nextId()).thenReturn(100L);
            when(mfaConfigRepository.deleteByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.empty());
            when(mfaConfigRepository.save(any(UserMfaConfig.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(mfaService.setupTotp(USER_ID, USER_EMAIL))
                    .assertNext(response -> {
                        assertThat(response.getMethod()).isEqualTo("TOTP");
                        assertThat(response.getSecretKey()).isNotNull().isNotEmpty();
                        assertThat(response.getQrCodeDataUri()).startsWith("data:image/png;base64,");
                    })
                    .verifyComplete();

            verify(mfaConfigRepository).deleteByUserIdAndMethod(USER_ID, "TOTP");
            verify(mfaConfigRepository).save(any(UserMfaConfig.class));
        }

        @Test
        @DisplayName("Should save unverified MFA config during setup")
        void setupTotp_ShouldSaveUnverifiedConfig() {
            when(aesEncryptor.encrypt(anyString())).thenReturn(ENCRYPTED_SECRET);
            when(idService.nextId()).thenReturn(100L);
            when(mfaConfigRepository.deleteByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.empty());

            ArgumentCaptor<UserMfaConfig> configCaptor = ArgumentCaptor.forClass(UserMfaConfig.class);
            when(mfaConfigRepository.save(configCaptor.capture())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(mfaService.setupTotp(USER_ID, USER_EMAIL))
                    .expectNextCount(1)
                    .verifyComplete();

            UserMfaConfig savedConfig = configCaptor.getValue();
            assertThat(savedConfig.getUserId()).isEqualTo(USER_ID);
            assertThat(savedConfig.getMethod()).isEqualTo("TOTP");
            assertThat(savedConfig.getSecretEncrypted()).isEqualTo(ENCRYPTED_SECRET);
            assertThat(savedConfig.getVerified()).isFalse();
            assertThat(savedConfig.getId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should delete existing unverified config before creating new one")
        void setupTotp_ShouldDeleteExistingConfig() {
            when(aesEncryptor.encrypt(anyString())).thenReturn(ENCRYPTED_SECRET);
            when(idService.nextId()).thenReturn(100L);
            when(mfaConfigRepository.deleteByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.empty());
            when(mfaConfigRepository.save(any(UserMfaConfig.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(mfaService.setupTotp(USER_ID, USER_EMAIL))
                    .expectNextCount(1)
                    .verifyComplete();

            var inOrder = inOrder(mfaConfigRepository);
            inOrder.verify(mfaConfigRepository).deleteByUserIdAndMethod(USER_ID, "TOTP");
            inOrder.verify(mfaConfigRepository).save(any(UserMfaConfig.class));
        }
    }

    // ==================== verifySetup ====================

    @Nested
    @DisplayName("verifySetup")
    class VerifySetupTests {

        @Test
        @DisplayName("Should return empty list when no TOTP config found")
        void verifySetup_ShouldReturnEmptyList_WhenNoConfigFound() {
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.empty());

            StepVerifier.create(mfaService.verifySetup(USER_ID, "123456"))
                    .assertNext(codes -> assertThat(codes).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty list when config is already verified")
        void verifySetup_ShouldReturnEmptyList_WhenAlreadyVerified() {
            UserMfaConfig verifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(true)
                    .build();
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(verifiedConfig));

            StepVerifier.create(mfaService.verifySetup(USER_ID, "123456"))
                    .assertNext(codes -> assertThat(codes).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty list when TOTP code is invalid")
        void verifySetup_ShouldReturnEmptyList_WhenCodeInvalid() {
            UserMfaConfig unverifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(false)
                    .build();
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(unverifiedConfig));
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(RAW_SECRET);

            // The code "000000" will not match the generated TOTP
            StepVerifier.create(mfaService.verifySetup(USER_ID, "000000"))
                    .assertNext(codes -> assertThat(codes).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should mark config as verified and generate backup codes on valid code")
        void verifySetup_ShouldVerifyAndGenerateBackupCodes_WhenCodeValid() {
            // We need a real TOTP secret to generate a valid code.
            // Instead, we mock the internal flow by using a spy or by testing
            // the observable behavior when a valid code is provided.
            // Since verifyTotpCode is private, we test the full chain by using
            // a known secret and generating the matching TOTP code.
            // For simplicity, we verify the flow when verifySetup proceeds past validation.

            // Create a real base64 secret and compute the matching TOTP
            // This is hard to do without real TOTP generation, so we test
            // the "invalid code" path above and the "already verified" path.
            // The backup code generation path is tested separately.
            // This test verifies the overall wiring is correct.

            UserMfaConfig unverifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(false)
                    .build();
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(unverifiedConfig));
            // Use a real base64-encoded HMAC-SHA1 key (20 bytes)
            String realBase64Secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAA";
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(realBase64Secret);

            // Generate the matching TOTP code
            String totpCode = generateTotpCode(realBase64Secret, 6, 30);

            when(mfaConfigRepository.save(any(UserMfaConfig.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(userRepository.findById(USER_ID)).thenReturn(Mono.just(testUser));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(auditService.logMfaEnabled(eq(USER_ID), eq(USER_EMAIL), eq("TOTP"))).thenReturn(Mono.empty());
            when(backupCodeRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(200L);
            when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
            when(backupCodeRepository.save(any(MfaBackupCode.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(mfaService.verifySetup(USER_ID, totpCode))
                    .assertNext(codes -> assertThat(codes).hasSize(10))
                    .verifyComplete();

            verify(mfaConfigRepository).save(argThat(config ->
                    config.getVerified() && !config.isNewRecord()));
            verify(userRepository).save(argThat(user ->
                    Boolean.TRUE.equals(user.getMfaEnabled()) && "TOTP".equals(user.getMfaPreferredMethod())));
            verify(auditService).logMfaEnabled(USER_ID, USER_EMAIL, "TOTP");
        }
    }

    // ==================== verifyTotp ====================

    @Nested
    @DisplayName("verifyTotp")
    class VerifyTotpTests {

        @Test
        @DisplayName("Should return false when brute force limit is exceeded")
        void verifyTotp_ShouldReturnFalse_WhenBruteForceLimitExceeded() {
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(6L));

            StepVerifier.create(mfaService.verifyTotp(USER_ID, "123456"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();

            verify(mfaConfigRepository, never()).findByUserIdAndMethod(any(), any());
        }

        @Test
        @DisplayName("Should set expiry on first attempt")
        void verifyTotp_ShouldSetExpiry_OnFirstAttempt() {
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(1L));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.empty());

            StepVerifier.create(mfaService.verifyTotp(USER_ID, "123456"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();

            verify(redisTemplate).expire(contains("mfa:totp-attempts:"), eq(Duration.ofMinutes(5)));
        }

        @Test
        @DisplayName("Should return false when no TOTP config found")
        void verifyTotp_ShouldReturnFalse_WhenNoConfigFound() {
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(1L));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.empty());

            StepVerifier.create(mfaService.verifyTotp(USER_ID, "123456"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when config is not verified")
        void verifyTotp_ShouldReturnFalse_WhenConfigNotVerified() {
            UserMfaConfig unverifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(false)
                    .build();
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(2L));
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(unverifiedConfig));

            StepVerifier.create(mfaService.verifyTotp(USER_ID, "123456"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when TOTP code is invalid")
        void verifyTotp_ShouldReturnFalse_WhenCodeInvalid() {
            UserMfaConfig verifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(true)
                    .build();
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(2L));
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(verifiedConfig));
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(RAW_SECRET);

            StepVerifier.create(mfaService.verifyTotp(USER_ID, "000000"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when TOTP code is reused")
        void verifyTotp_ShouldReturnFalse_WhenCodeReused() {
            String realBase64Secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAA";
            String totpCode = generateTotpCode(realBase64Secret, 6, 30);

            UserMfaConfig verifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(true)
                    .build();
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(2L));
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(verifiedConfig));
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(realBase64Secret);
            // setIfAbsent returns false (code already used)
            when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(mfaService.verifyTotp(USER_ID, totpCode))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return true and clear attempts when TOTP code is valid")
        void verifyTotp_ShouldReturnTrue_WhenCodeValid() {
            String realBase64Secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAA";
            String totpCode = generateTotpCode(realBase64Secret, 6, 30);

            UserMfaConfig verifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(true)
                    .build();
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(2L));
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(verifiedConfig));
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(realBase64Secret);
            // setIfAbsent returns true (code not reused)
            when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));

            StepVerifier.create(mfaService.verifyTotp(USER_ID, totpCode))
                    .assertNext(result -> assertThat(result).isTrue())
                    .verifyComplete();

            verify(redisTemplate).delete(contains("mfa:totp-attempts:"));
        }

        @Test
        @DisplayName("Should fall back to verify-only when Redis is unavailable")
        void verifyTotp_ShouldFallback_WhenRedisUnavailable() {
            String realBase64Secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAA";
            String totpCode = generateTotpCode(realBase64Secret, 6, 30);

            UserMfaConfig verifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(true)
                    .build();

            when(valueOperations.increment(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(verifiedConfig));
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(realBase64Secret);

            StepVerifier.create(mfaService.verifyTotp(USER_ID, totpCode))
                    .assertNext(result -> assertThat(result).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false in fallback mode when code is invalid")
        void verifyTotp_ShouldReturnFalseInFallback_WhenCodeInvalid() {
            UserMfaConfig verifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(true)
                    .build();

            when(valueOperations.increment(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(verifiedConfig));
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(RAW_SECRET);

            StepVerifier.create(mfaService.verifyTotp(USER_ID, "000000"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false in fallback when no config found")
        void verifyTotp_ShouldReturnFalseInFallback_WhenNoConfig() {
            when(valueOperations.increment(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis down")));
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.empty());

            StepVerifier.create(mfaService.verifyTotp(USER_ID, "123456"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should allow up to max attempts before blocking")
        void verifyTotp_ShouldAllow_WhenAtMaxAttempts() {
            UserMfaConfig verifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(true)
                    .build();
            // Exactly at max (5) should still be allowed
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(5L));
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(verifiedConfig));
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(RAW_SECRET);

            StepVerifier.create(mfaService.verifyTotp(USER_ID, "000000"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();

            // Verify the repository was still queried (not blocked)
            verify(mfaConfigRepository).findByUserIdAndMethod(USER_ID, "TOTP");
        }
    }

    // ==================== disableMfa ====================

    @Nested
    @DisplayName("disableMfa")
    class DisableMfaTests {

        @Test
        @DisplayName("Should delete all MFA configs and backup codes and disable MFA on user")
        void disableMfa_ShouldDisableCompletely() {
            when(mfaConfigRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());
            when(backupCodeRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Mono.just(testUser));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(auditService.logMfaDisabled(USER_ID, USER_EMAIL)).thenReturn(Mono.empty());

            StepVerifier.create(mfaService.disableMfa(USER_ID))
                    .verifyComplete();

            verify(mfaConfigRepository).deleteByUserId(USER_ID);
            verify(backupCodeRepository).deleteByUserId(USER_ID);
            verify(userRepository).save(argThat(user ->
                    Boolean.FALSE.equals(user.getMfaEnabled()) && user.getMfaPreferredMethod() == null));
            verify(auditService).logMfaDisabled(USER_ID, USER_EMAIL);
        }
    }

    // ==================== disableMethod ====================

    @Nested
    @DisplayName("disableMethod")
    class DisableMethodTests {

        @Test
        @DisplayName("Should fully disable MFA when last method is removed")
        void disableMethod_ShouldFullyDisable_WhenNoMethodsRemain() {
            when(mfaConfigRepository.deleteByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.empty());
            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.empty());
            when(backupCodeRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Mono.just(testUser));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(auditService.logMfaMethodDisabled(USER_ID, USER_EMAIL, "TOTP")).thenReturn(Mono.empty());

            StepVerifier.create(mfaService.disableMethod(USER_ID, "TOTP"))
                    .verifyComplete();

            verify(backupCodeRepository).deleteByUserId(USER_ID);
            verify(userRepository).save(argThat(user ->
                    Boolean.FALSE.equals(user.getMfaEnabled()) && user.getMfaPreferredMethod() == null));
            verify(auditService).logMfaMethodDisabled(USER_ID, USER_EMAIL, "TOTP");
        }

        @Test
        @DisplayName("Should update preferred method when remaining methods exist and preferred was disabled")
        void disableMethod_ShouldUpdatePreferred_WhenOtherMethodsRemain() {
            testUser.setMfaEnabled(true);
            testUser.setMfaPreferredMethod("TOTP");

            UserMfaConfig emailConfig = UserMfaConfig.builder()
                    .id(200L)
                    .userId(USER_ID)
                    .method("EMAIL")
                    .verified(true)
                    .build();

            when(mfaConfigRepository.deleteByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.empty());
            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.just(emailConfig));
            when(userRepository.findById(USER_ID)).thenReturn(Mono.just(testUser));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(auditService.logMfaMethodDisabled(USER_ID, USER_EMAIL, "TOTP")).thenReturn(Mono.empty());

            StepVerifier.create(mfaService.disableMethod(USER_ID, "TOTP"))
                    .verifyComplete();

            verify(backupCodeRepository, never()).deleteByUserId(any());
            verify(userRepository).save(argThat(user ->
                    "EMAIL".equals(user.getMfaPreferredMethod())));
        }

        @Test
        @DisplayName("Should not update preferred method when non-preferred method is disabled")
        void disableMethod_ShouldNotUpdatePreferred_WhenNonPreferredDisabled() {
            testUser.setMfaEnabled(true);
            testUser.setMfaPreferredMethod("TOTP");

            UserMfaConfig totpConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .verified(true)
                    .build();

            when(mfaConfigRepository.deleteByUserIdAndMethod(USER_ID, "EMAIL")).thenReturn(Mono.empty());
            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.just(totpConfig));
            when(userRepository.findById(USER_ID)).thenReturn(Mono.just(testUser));
            when(auditService.logMfaMethodDisabled(USER_ID, USER_EMAIL, "EMAIL")).thenReturn(Mono.empty());

            StepVerifier.create(mfaService.disableMethod(USER_ID, "EMAIL"))
                    .verifyComplete();

            // User should not be saved again (preferred method unchanged)
            verify(userRepository, never()).save(any(User.class));
        }
    }

    // ==================== verifyAnyCode ====================

    @Nested
    @DisplayName("verifyAnyCode")
    class VerifyAnyCodeTests {

        @Test
        @DisplayName("Should return false when no verified MFA configs exist")
        void verifyAnyCode_ShouldReturnFalse_WhenNoConfigs() {
            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.empty());

            StepVerifier.create(mfaService.verifyAnyCode(USER_ID, "123456"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when only unverified configs exist")
        void verifyAnyCode_ShouldReturnFalse_WhenOnlyUnverifiedConfigs() {
            UserMfaConfig unverifiedConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(false)
                    .build();
            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.just(unverifiedConfig));

            StepVerifier.create(mfaService.verifyAnyCode(USER_ID, "123456"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should verify TOTP code against verified TOTP config")
        void verifyAnyCode_ShouldVerifyTotpCode() {
            String realBase64Secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAA";
            String totpCode = generateTotpCode(realBase64Secret, 6, 30);

            UserMfaConfig totpConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(true)
                    .build();
            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.just(totpConfig));
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(realBase64Secret);
            // SEG-8: verifyAnyCode now routes TOTP through the hardened verifyTotp(),
            // which loads the config itself and runs the Redis attempts/reuse-lock ops.
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(totpConfig));
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(1L));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            // setIfAbsent returns true (code not reused), then attempts counter is cleared
            when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));

            StepVerifier.create(mfaService.verifyAnyCode(USER_ID, totpCode))
                    .assertNext(result -> assertThat(result).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should fall back to email OTP when TOTP fails")
        void verifyAnyCode_ShouldFallBackToEmail_WhenTotpFails() {
            UserMfaConfig totpConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(true)
                    .build();
            UserMfaConfig emailConfig = UserMfaConfig.builder()
                    .id(200L)
                    .userId(USER_ID)
                    .method("EMAIL")
                    .verified(true)
                    .build();

            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.just(totpConfig, emailConfig));
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(RAW_SECRET);
            // SEG-8: TOTP now goes through hardened verifyTotp() — it loads the config and
            // runs the attempts counter. The wrong code fails verifyTotpCode, so no reuse-lock
            // or counter-clear ops are reached; verifyTotp returns false and email OTP is tried.
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(totpConfig));
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(1L));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            // TOTP will fail with wrong code, then email OTP should be tried
            when(emailOtpService.verifyOtp(USER_ID, "654321")).thenReturn(Mono.just(true));

            StepVerifier.create(mfaService.verifyAnyCode(USER_ID, "654321"))
                    .assertNext(result -> assertThat(result).isTrue())
                    .verifyComplete();

            verify(emailOtpService).verifyOtp(USER_ID, "654321");
        }

        @Test
        @DisplayName("Should verify email OTP when only email config exists")
        void verifyAnyCode_ShouldVerifyEmailOtp_WhenOnlyEmailConfig() {
            UserMfaConfig emailConfig = UserMfaConfig.builder()
                    .id(200L)
                    .userId(USER_ID)
                    .method("EMAIL")
                    .verified(true)
                    .build();

            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.just(emailConfig));
            when(emailOtpService.verifyOtp(USER_ID, "123456")).thenReturn(Mono.just(true));

            StepVerifier.create(mfaService.verifyAnyCode(USER_ID, "123456"))
                    .assertNext(result -> assertThat(result).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when all methods fail verification")
        void verifyAnyCode_ShouldReturnFalse_WhenAllMethodsFail() {
            UserMfaConfig totpConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .secretEncrypted(ENCRYPTED_SECRET)
                    .verified(true)
                    .build();
            UserMfaConfig emailConfig = UserMfaConfig.builder()
                    .id(200L)
                    .userId(USER_ID)
                    .method("EMAIL")
                    .verified(true)
                    .build();

            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.just(totpConfig, emailConfig));
            when(aesEncryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(RAW_SECRET);
            // SEG-8: TOTP routes through hardened verifyTotp(); wrong code fails verifyTotpCode
            // so verifyTotp returns false without reaching reuse-lock/counter-clear, then email fails too.
            when(mfaConfigRepository.findByUserIdAndMethod(USER_ID, "TOTP")).thenReturn(Mono.just(totpConfig));
            when(valueOperations.increment(anyString())).thenReturn(Mono.just(1L));
            when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            when(emailOtpService.verifyOtp(USER_ID, "000000")).thenReturn(Mono.just(false));

            StepVerifier.create(mfaService.verifyAnyCode(USER_ID, "000000"))
                    .assertNext(result -> assertThat(result).isFalse())
                    .verifyComplete();
        }
    }

    // ==================== getStatus ====================

    @Nested
    @DisplayName("getStatus")
    class GetStatusTests {

        @Test
        @DisplayName("Should return MFA status with enabled methods")
        void getStatus_ShouldReturnStatus_WhenMfaEnabled() {
            UserMfaConfig totpConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .verified(true)
                    .build();
            testUser.setMfaEnabled(true);
            testUser.setMfaPreferredMethod("TOTP");

            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.just(totpConfig));
            when(userRepository.findById(USER_ID)).thenReturn(Mono.just(testUser));
            when(backupCodeRepository.countByUserIdAndUsedFalse(USER_ID)).thenReturn(Mono.just(8L));

            StepVerifier.create(mfaService.getStatus(USER_ID))
                    .assertNext(status -> {
                        assertThat(status.isMfaEnabled()).isTrue();
                        assertThat(status.getMethods()).containsExactly("TOTP");
                        assertThat(status.getPreferredMethod()).isEqualTo("TOTP");
                        assertThat(status.getBackupCodesRemaining()).isEqualTo(8L);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return multiple methods when both TOTP and EMAIL are configured")
        void getStatus_ShouldReturnMultipleMethods() {
            UserMfaConfig totpConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .verified(true)
                    .build();
            UserMfaConfig emailConfig = UserMfaConfig.builder()
                    .id(200L)
                    .userId(USER_ID)
                    .method("EMAIL")
                    .verified(true)
                    .build();
            testUser.setMfaEnabled(true);
            testUser.setMfaPreferredMethod("TOTP");

            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.just(totpConfig, emailConfig));
            when(userRepository.findById(USER_ID)).thenReturn(Mono.just(testUser));
            when(backupCodeRepository.countByUserIdAndUsedFalse(USER_ID)).thenReturn(Mono.just(5L));

            StepVerifier.create(mfaService.getStatus(USER_ID))
                    .assertNext(status -> {
                        assertThat(status.isMfaEnabled()).isTrue();
                        assertThat(status.getMethods()).containsExactly("TOTP", "EMAIL");
                        assertThat(status.getBackupCodesRemaining()).isEqualTo(5L);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return disabled status when no verified configs exist")
        void getStatus_ShouldReturnDisabled_WhenNoVerifiedConfigs() {
            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Mono.empty());
            when(backupCodeRepository.countByUserIdAndUsedFalse(USER_ID)).thenReturn(Mono.empty());

            StepVerifier.create(mfaService.getStatus(USER_ID))
                    .assertNext(status -> {
                        assertThat(status.isMfaEnabled()).isFalse();
                        assertThat(status.getMethods()).isEmpty();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return zero backup codes when count returns empty")
        void getStatus_ShouldReturnZeroBackupCodes_WhenCountEmpty() {
            UserMfaConfig totpConfig = UserMfaConfig.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .method("TOTP")
                    .verified(true)
                    .build();
            testUser.setMfaEnabled(true);
            testUser.setMfaPreferredMethod("TOTP");

            when(mfaConfigRepository.findByUserId(USER_ID)).thenReturn(Flux.just(totpConfig));
            when(userRepository.findById(USER_ID)).thenReturn(Mono.just(testUser));
            when(backupCodeRepository.countByUserIdAndUsedFalse(USER_ID)).thenReturn(Mono.empty());

            StepVerifier.create(mfaService.getStatus(USER_ID))
                    .assertNext(status ->
                            assertThat(status.getBackupCodesRemaining()).isZero())
                    .verifyComplete();
        }
    }

    // ==================== generateBackupCodes ====================

    @Nested
    @DisplayName("generateBackupCodes")
    class GenerateBackupCodesTests {

        @Test
        @DisplayName("Should generate 10 backup codes and save them")
        void generateBackupCodes_ShouldGenerate10Codes() {
            when(backupCodeRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(300L);
            when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
            when(backupCodeRepository.save(any(MfaBackupCode.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(mfaService.generateBackupCodes(USER_ID))
                    .assertNext(codes -> {
                        assertThat(codes).hasSize(10);
                        // Each code should be in XXXX-XXXX format
                        codes.forEach(code -> assertThat(code).matches("[a-z2-9]{4}-[a-z2-9]{4}"));
                    })
                    .verifyComplete();

            verify(backupCodeRepository).deleteByUserId(USER_ID);
            verify(backupCodeRepository, times(10)).save(any(MfaBackupCode.class));
        }

        @Test
        @DisplayName("Should delete existing backup codes before generating new ones")
        void generateBackupCodes_ShouldDeleteExistingFirst() {
            when(backupCodeRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(300L);
            when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
            when(backupCodeRepository.save(any(MfaBackupCode.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(mfaService.generateBackupCodes(USER_ID))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(backupCodeRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("Should hash backup codes using password encoder")
        void generateBackupCodes_ShouldHashCodes() {
            when(backupCodeRepository.deleteByUserId(USER_ID)).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(300L);
            when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");

            ArgumentCaptor<MfaBackupCode> codeCaptor = ArgumentCaptor.forClass(MfaBackupCode.class);
            when(backupCodeRepository.save(codeCaptor.capture())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(mfaService.generateBackupCodes(USER_ID))
                    .expectNextCount(1)
                    .verifyComplete();

            List<MfaBackupCode> savedCodes = codeCaptor.getAllValues();
            assertThat(savedCodes).hasSize(10);
            savedCodes.forEach(code -> {
                assertThat(code.getCodeHash()).isEqualTo("bcrypt-hash");
                assertThat(code.getUserId()).isEqualTo(USER_ID);
                assertThat(code.getUsed()).isFalse();
            });

            // passwordEncoder.encode is called with normalized codes (no dashes, lowercase)
            verify(passwordEncoder, times(10)).encode(argThat((CharSequence input) -> {
                    String s = input.toString();
                    return !s.contains("-") && s.equals(s.toLowerCase());
            }));
        }
    }

    // ==================== verifyBackupCode ====================

    @Nested
    @DisplayName("verifyBackupCode")
    class VerifyBackupCodeTests {

        @Test
        @DisplayName("Should return true and mark code as used when backup code matches")
        void verifyBackupCode_ShouldReturnTrue_WhenCodeMatches() {
            MfaBackupCode backupCode = MfaBackupCode.builder()
                    .id(400L)
                    .userId(USER_ID)
                    .codeHash("bcrypt-hash")
                    .used(false)
                    .build();

            when(backupCodeRepository.findByUserIdAndUsedFalse(USER_ID))
                    .thenReturn(Flux.just(backupCode));
            when(passwordEncoder.matches("abcd1234", "bcrypt-hash")).thenReturn(true);
            when(backupCodeRepository.save(any(MfaBackupCode.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(mfaService.verifyBackupCode(USER_ID, "abcd-1234"))
                    .assertNext(result -> assertThat(result).isTrue())
                    .verifyComplete();

            verify(backupCodeRepository).save(argThat(bc ->
                    bc.getUsed() && bc.getUsedAt() != null && !bc.isNewRecord()));
        }

        @Test
        @DisplayName("Should return false when no backup codes match")
        void verifyBackupCode_ShouldReturnFalse_WhenNoMatch() {
            MfaBackupCode backupCode = MfaBackupCode.builder()
                    .id(400L)
                    .userId(USER_ID)
                    .codeHash("bcrypt-hash")
                    .used(false)
                    .build();

            when(backupCodeRepository.findByUserIdAndUsedFalse(USER_ID))
                    .thenReturn(Flux.just(backupCode));
            when(passwordEncoder.matches(anyString(), eq("bcrypt-hash"))).thenReturn(false);

            StepVerifier.create(mfaService.verifyBackupCode(USER_ID, "wrong-code"))
                    .verifyComplete();

            verify(backupCodeRepository, never()).save(any(MfaBackupCode.class));
        }

        @Test
        @DisplayName("Should return false when no unused backup codes exist")
        void verifyBackupCode_ShouldReturnFalse_WhenNoUnusedCodes() {
            when(backupCodeRepository.findByUserIdAndUsedFalse(USER_ID))
                    .thenReturn(Flux.empty());

            StepVerifier.create(mfaService.verifyBackupCode(USER_ID, "abcd-1234"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should normalize code by removing dashes, spaces, and lowering case")
        void verifyBackupCode_ShouldNormalizeCode() {
            MfaBackupCode backupCode = MfaBackupCode.builder()
                    .id(400L)
                    .userId(USER_ID)
                    .codeHash("bcrypt-hash")
                    .used(false)
                    .build();

            when(backupCodeRepository.findByUserIdAndUsedFalse(USER_ID))
                    .thenReturn(Flux.just(backupCode));
            when(passwordEncoder.matches("abcd1234", "bcrypt-hash")).thenReturn(true);
            when(backupCodeRepository.save(any(MfaBackupCode.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            // Code with uppercase, spaces, and dashes should be normalized
            StepVerifier.create(mfaService.verifyBackupCode(USER_ID, "  ABCD - 1234  "))
                    .assertNext(result -> assertThat(result).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should match against the correct code among multiple backup codes")
        void verifyBackupCode_ShouldMatchCorrectCode_AmongMultiple() {
            MfaBackupCode code1 = MfaBackupCode.builder()
                    .id(401L).userId(USER_ID).codeHash("hash-1").used(false).build();
            MfaBackupCode code2 = MfaBackupCode.builder()
                    .id(402L).userId(USER_ID).codeHash("hash-2").used(false).build();
            MfaBackupCode code3 = MfaBackupCode.builder()
                    .id(403L).userId(USER_ID).codeHash("hash-3").used(false).build();

            when(backupCodeRepository.findByUserIdAndUsedFalse(USER_ID))
                    .thenReturn(Flux.just(code1, code2, code3));
            when(passwordEncoder.matches("testcode", "hash-1")).thenReturn(false);
            when(passwordEncoder.matches("testcode", "hash-2")).thenReturn(true);
            when(backupCodeRepository.save(any(MfaBackupCode.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(mfaService.verifyBackupCode(USER_ID, "test-code"))
                    .assertNext(result -> assertThat(result).isTrue())
                    .verifyComplete();

            // Verify the correct code (code2) was marked as used
            verify(backupCodeRepository).save(argThat(bc -> bc.getId().equals(402L) && bc.getUsed()));
        }
    }

    // ==================== getRemainingBackupCodeCount ====================

    @Nested
    @DisplayName("getRemainingBackupCodeCount")
    class GetRemainingBackupCodeCountTests {

        @Test
        @DisplayName("Should return count of unused backup codes")
        void getRemainingBackupCodeCount_ShouldReturnCount() {
            when(backupCodeRepository.countByUserIdAndUsedFalse(USER_ID)).thenReturn(Mono.just(7L));

            StepVerifier.create(mfaService.getRemainingBackupCodeCount(USER_ID))
                    .assertNext(count -> assertThat(count).isEqualTo(7L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return zero when no backup codes exist")
        void getRemainingBackupCodeCount_ShouldReturnZero_WhenNoCodes() {
            when(backupCodeRepository.countByUserIdAndUsedFalse(USER_ID)).thenReturn(Mono.just(0L));

            StepVerifier.create(mfaService.getRemainingBackupCodeCount(USER_ID))
                    .assertNext(count -> assertThat(count).isZero())
                    .verifyComplete();
        }
    }

    // ==================== Helper ====================

    /**
     * Generate a real TOTP code for the given base64 secret to use in tests.
     * Uses the same algorithm as the service's verifyTotpCode method.
     */
    private static String generateTotpCode(String base64Secret, int digits, int periodSeconds) {
        try {
            byte[] keyBytes = java.util.Base64.getDecoder().decode(base64Secret);
            javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA1");
            var generator = new com.eatthepath.otp.TimeBasedOneTimePasswordGenerator(
                    Duration.ofSeconds(periodSeconds), digits);
            java.time.Instant now = java.time.Instant.now();
            return String.format("%0" + digits + "d", generator.generateOneTimePassword(key, now));
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test TOTP code", e);
        }
    }
}
