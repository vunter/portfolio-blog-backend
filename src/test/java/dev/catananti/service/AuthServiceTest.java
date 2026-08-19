package dev.catananti.service;

import dev.catananti.dto.LoginRequest;
import dev.catananti.dto.RegisterRequest;
import dev.catananti.dto.TokenResponse;
import dev.catananti.entity.RefreshToken;
import dev.catananti.entity.User;
import dev.catananti.exception.AccountLockedException;
import dev.catananti.repository.UserRepository;
import dev.catananti.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private LoginAttemptService loginAttemptService;
    
    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private MessageSource messageSource;

    @Mock
    private IdService idService;

    @Mock
    private HtmlSanitizerService htmlSanitizerService;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private EmailService emailService;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private AuditService auditService;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private org.springframework.data.redis.core.ReactiveValueOperations<String, String> valueOperations;

    @Mock
    private MfaService mfaService;

    private AuthService authService;

    private User testUser;

    @org.mockito.Mock
    private org.springframework.transaction.reactive.TransactionalOperator transactionalOperator;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, passwordEncoder, tokenProvider,
                refreshTokenService, messageSource, idService,
                htmlSanitizerService, tokenBlacklistService, emailService,
                emailVerificationService, auditService, redisTemplate, transactionalOperator,
                loginAttemptService, null, null);
        // Set jwtExpirationMs via reflection
        try {
            var field = AuthService.class.getDeclaredField("jwtExpirationMs");
            field.setAccessible(true);
            field.set(authService, 86400000L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        lenient().when(transactionalOperator.transactional(any(reactor.core.publisher.Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenReturn("Invalid credentials");
        lenient().when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
        // AUD19-LOGIN: the success paths fire-and-forget an audit write; without this
        // stub the mock returns null and the detached subscribe would NPE.
        lenient().when(auditService.logLoginSuccess(anyLong(), anyString(), any()))
                .thenReturn(Mono.empty());

        testUser = User.builder()
                .id(1234567890123456789L)
                .email("test@example.com")
                .passwordHash("$2a$10$hashedpassword")
                .name("Test User")
                .role("ADMIN")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // AUD18-M7: tests for the removed login()/performLogin() dead path were deleted with
    // it — the equivalent lockout/credential coverage lives in the loginWithRefreshToken
    // tests below, which exercise the same verifyCredentials() core.

    @Test
    @DisplayName("Should validate token")
    void validateToken_ShouldReturnTrue_WhenTokenValid() {
        // Given
        String token = "valid-jwt-token";
        when(tokenProvider.validateToken(token)).thenReturn(true);

        // When
        boolean result = authService.validateToken(token);

        // Then
        assertThat(result).isTrue();
        verify(tokenProvider).validateToken(token);
    }

    // ==================== ADDED TESTS ====================

    @Test
    @DisplayName("Should lock account when remaining attempts reach 0")
    void loginWithRefreshToken_ShouldLockAccount_WhenNoRemainingAttempts() {
        LoginRequest request = new LoginRequest("test@example.com", "wrongpass", false, null);
        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(false));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("wrongpass", testUser.getPasswordHash())).thenReturn(false);
        when(loginAttemptService.recordFailedAttempt(eq("test@example.com"), anyString())).thenReturn(Mono.just(5));
        when(loginAttemptService.getRemainingAttempts("test@example.com")).thenReturn(Mono.just(0));

        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1", null))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    @Test
    @DisplayName("Should login with refresh token successfully")
    void loginWithRefreshToken_ShouldReturnTokenResponse() {
        LoginRequest request = new LoginRequest("test@example.com", "password123", false, null);
        RefreshToken refreshToken = RefreshToken.builder()
                .id(1L).userId(testUser.getId()).token("refresh-tok").build();

        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(false));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("password123", testUser.getPasswordHash())).thenReturn(true);
        when(loginAttemptService.clearFailedAttempts("test@example.com")).thenReturn(Mono.empty());
        when(tokenProvider.generateToken(testUser.getId(), "ADMIN")).thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(testUser.getId(), "127.0.0.1", "TestAgent")).thenReturn(Mono.just(refreshToken));

        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1", "TestAgent"))
                .assertNext(resp -> {
                    assertThat(resp.getAccessToken()).isEqualTo("access-token");
                    assertThat(resp.getRefreshToken()).isEqualTo("refresh-tok");
                    assertThat(resp.getTokenType()).isEqualTo("Bearer");
                    assertThat(resp.getEmail()).isEqualTo("test@example.com");
                })
                .verifyComplete();

        // AUD19-LOGIN: a successful password login writes a LOGIN audit row
        verify(auditService).logLoginSuccess(testUser.getId(), "test@example.com", "127.0.0.1");
    }

    @Test
    @DisplayName("Should throw when loginWithRefreshToken account is locked")
    void loginWithRefreshToken_ShouldThrow_WhenLocked() {
        LoginRequest request = new LoginRequest("test@example.com", "pass", false, null);
        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(true));
        when(loginAttemptService.getRemainingLockoutTime("test@example.com")).thenReturn(Mono.just(120000L));

        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1", null))
                .expectError(AccountLockedException.class)
                .verify();

        // AUD19-LOGIN: no success audit when the account is locked
        verify(auditService, never()).logLoginSuccess(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("Should throw when loginWithRefreshToken password wrong")
    void loginWithRefreshToken_ShouldThrow_WhenPasswordWrong() {
        LoginRequest request = new LoginRequest("test@example.com", "badpass", false, null);
        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(false));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("badpass", testUser.getPasswordHash())).thenReturn(false);
        when(loginAttemptService.recordFailedAttempt(eq("test@example.com"), anyString())).thenReturn(Mono.just(1));
        when(loginAttemptService.getRemainingAttempts("test@example.com")).thenReturn(Mono.just(4));

        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1", null))
                .expectError(BadCredentialsException.class)
                .verify();

        // AUD19-LOGIN: failed logins must never write a LOGIN (success) audit row
        verify(auditService, never()).logLoginSuccess(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("AUD19C-DEACT: Should throw AccountDeactivatedException on correct password + deactivated account")
    void loginWithRefreshToken_ShouldThrowAccountDeactivated_WhenUserDeactivated() {
        User deactivated = User.builder()
                .id(1234567890123456789L).email("test@example.com").name("Test User")
                .passwordHash("$2a$10$hashedpassword")
                .role("ADMIN").active(false).build();
        LoginRequest request = new LoginRequest("test@example.com", "password123", false, null);

        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(false));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(deactivated));
        when(passwordEncoder.matches("password123", deactivated.getPasswordHash())).thenReturn(true);

        // A dedicated exception (handled as 403 error.account_deactivated) instead of the
        // old BadCredentialsException, which was masked as "invalid credentials" and
        // told the holder their correct password was wrong.
        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1", null))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(dev.catananti.exception.AccountDeactivatedException.class);
                    assertThat(err.getMessage()).isEqualTo("error.account_deactivated");
                })
                .verify();

        // No token must ever be minted for a deactivated account
        verify(tokenProvider, never()).generateToken(anyLong(), anyString());
        verify(refreshTokenService, never()).createRefreshToken(anyLong(), any(), any());
        verify(auditService, never()).logLoginSuccess(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("AUD19C-MFA429: Should reject with localized key after too many MFA attempts")
    void completeMfaLogin_ShouldReturnLocalizedKey_WhenTooManyAttempts() {
        AuthService mfaAuthService = new AuthService(
                userRepository, passwordEncoder, tokenProvider,
                refreshTokenService, messageSource, idService,
                htmlSanitizerService, tokenBlacklistService, emailService,
                emailVerificationService, auditService, redisTemplate, transactionalOperator,
                loginAttemptService, mfaService, null);
        try {
            var field = AuthService.class.getDeclaredField("maxMfaAttempts");
            field.setAccessible(true);
            field.set(mfaAuthService, 5);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(startsWith("mfa:token:"))).thenReturn(Mono.just("100"));
        when(valueOperations.increment(startsWith("mfa:attempts:"))).thenReturn(Mono.just(6L));
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));

        StepVerifier.create(mfaAuthService.completeMfaLogin("some-mfa-token", "123456", "TOTP", "127.0.0.1", "UA"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(429);
                    // i18n key, not the old hardcoded English sentence
                    assertThat(((ResponseStatusException) err).getReason()).isEqualTo("error.mfa_too_many_attempts");
                })
                .verify();
    }

    @Test
    @DisplayName("Should throw when loginWithRefreshToken user not found")
    void loginWithRefreshToken_ShouldThrow_WhenUserNotFound() {
        LoginRequest request = new LoginRequest("ghost@example.com", "pass", false, null);
        when(loginAttemptService.isBlocked("ghost@example.com")).thenReturn(Mono.just(false));
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Mono.empty());
        when(loginAttemptService.recordFailedAttempt(eq("ghost@example.com"), anyString())).thenReturn(Mono.just(1));

        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1", null))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    @Test
    @DisplayName("Should refresh access token successfully")
    void refreshAccessToken_ShouldReturnNewTokens() {
        RefreshToken newRefreshToken = RefreshToken.builder()
                .id(2L).userId(testUser.getId()).token("new-refresh-tok").build();

        when(refreshTokenService.verifyAndRotate("old-refresh-tok", null, null)).thenReturn(Mono.just(newRefreshToken));
        when(userRepository.findById(testUser.getId())).thenReturn(Mono.just(testUser));
        when(tokenProvider.generateToken(testUser.getId(), "ADMIN")).thenReturn("new-access-token");

        StepVerifier.create(authService.refreshAccessToken("old-refresh-tok", null, null))
                .assertNext(resp -> {
                    assertThat(resp.getAccessToken()).isEqualTo("new-access-token");
                    assertThat(resp.getRefreshToken()).isEqualTo("new-refresh-tok");
                    assertThat(resp.getEmail()).isEqualTo("test@example.com");
                })
                .verifyComplete();

        // AUD19-LOGIN: refresh-token rotation is NOT a login — no LOGIN audit row,
        // otherwise lastLogin (AUD19-F140) would be bumped by every silent refresh
        verify(auditService, never()).logLoginSuccess(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("Should throw when user not found during refresh")
    void refreshAccessToken_ShouldThrow_WhenUserNotFound() {
        RefreshToken newRefreshToken = RefreshToken.builder()
                .id(2L).userId(999L).token("tok").build();

        when(refreshTokenService.verifyAndRotate("tok", null, null)).thenReturn(Mono.just(newRefreshToken));
        when(userRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(authService.refreshAccessToken("tok", null, null))
                .expectError(SecurityException.class)
                .verify();
    }

    @Test
    @DisplayName("AUD18-L9: Should reject refresh and revoke all tokens when user is deactivated")
    void refreshAccessToken_ShouldReject_WhenUserDeactivated() {
        User deactivated = User.builder()
                .id(testUser.getId()).email("test@example.com").name("Test User")
                .role("ADMIN").active(false).build();
        RefreshToken newRefreshToken = RefreshToken.builder()
                .id(2L).userId(testUser.getId()).token("rotated-tok").build();

        when(refreshTokenService.verifyAndRotate("old-tok", null, null)).thenReturn(Mono.just(newRefreshToken));
        when(userRepository.findById(testUser.getId())).thenReturn(Mono.just(deactivated));
        when(refreshTokenService.revokeAllUserTokens(testUser.getId())).thenReturn(Mono.empty());

        StepVerifier.create(authService.refreshAccessToken("old-tok", null, null))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(SecurityException.class);
                    assertThat(err.getMessage()).isEqualTo("error.account_deactivated");
                })
                .verify();

        // The rotated token (and every other session) must be revoked, and no JWT minted
        verify(refreshTokenService).revokeAllUserTokens(testUser.getId());
        verify(tokenProvider, never()).generateToken(anyLong(), anyString());
    }

    @Test
    @DisplayName("AUD18-L9: Should reject MFA completion when user was deactivated mid-challenge")
    void completeMfaLogin_ShouldReject_WhenUserDeactivated() {
        AuthService mfaAuthService = new AuthService(
                userRepository, passwordEncoder, tokenProvider,
                refreshTokenService, messageSource, idService,
                htmlSanitizerService, tokenBlacklistService, emailService,
                emailVerificationService, auditService, redisTemplate, transactionalOperator,
                loginAttemptService, mfaService, null);
        try {
            var field = AuthService.class.getDeclaredField("maxMfaAttempts");
            field.setAccessible(true);
            field.set(mfaAuthService, 5);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        User deactivated = User.builder()
                .id(100L).email("test@example.com").name("Test User")
                .role("VIEWER").active(false).build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(startsWith("mfa:token:"))).thenReturn(Mono.just("100"));
        when(valueOperations.increment(startsWith("mfa:attempts:"))).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(startsWith("mfa:attempts:"), any())).thenReturn(Mono.just(true));
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
        when(mfaService.verifyTotp(100L, "123456")).thenReturn(Mono.just(true));
        when(userRepository.findById(100L)).thenReturn(Mono.just(deactivated));

        StepVerifier.create(mfaAuthService.completeMfaLogin("some-mfa-token", "123456", "TOTP", "127.0.0.1", "UA"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) err).getStatusCode().value()).isEqualTo(401);
                    assertThat(((ResponseStatusException) err).getReason()).isEqualTo("error.account_deactivated");
                })
                .verify();

        // A correct OTP for a deactivated account must never mint tokens
        verify(refreshTokenService, never()).createRefreshToken(anyLong(), any(), any());
        verify(tokenProvider, never()).generateToken(anyLong(), anyString());
        // AUD19-LOGIN: and never write a LOGIN success audit row
        verify(auditService, never()).logLoginSuccess(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("AUD19-LOGIN: Should write LOGIN audit row on successful MFA completion")
    void completeMfaLogin_ShouldLogLoginAudit_WhenOtpValid() {
        AuthService mfaAuthService = new AuthService(
                userRepository, passwordEncoder, tokenProvider,
                refreshTokenService, messageSource, idService,
                htmlSanitizerService, tokenBlacklistService, emailService,
                emailVerificationService, auditService, redisTemplate, transactionalOperator,
                loginAttemptService, mfaService, null);
        try {
            var attemptsField = AuthService.class.getDeclaredField("maxMfaAttempts");
            attemptsField.setAccessible(true);
            attemptsField.set(mfaAuthService, 5);
            var expirationField = AuthService.class.getDeclaredField("jwtExpirationMs");
            expirationField.setAccessible(true);
            expirationField.set(mfaAuthService, 86400000L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        User activeUser = User.builder()
                .id(100L).email("test@example.com").name("Test User")
                .role("ADMIN").active(true).mfaEnabled(true).build();
        RefreshToken refreshToken = RefreshToken.builder()
                .id(3L).userId(100L).token("mfa-refresh-tok").build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(startsWith("mfa:token:"))).thenReturn(Mono.just("100"));
        when(valueOperations.increment(startsWith("mfa:attempts:"))).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(startsWith("mfa:attempts:"), any())).thenReturn(Mono.just(true));
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
        when(mfaService.verifyTotp(100L, "123456")).thenReturn(Mono.just(true));
        when(userRepository.findById(100L)).thenReturn(Mono.just(activeUser));
        when(tokenProvider.generateToken(100L, "ADMIN")).thenReturn("mfa-access-tok");
        when(refreshTokenService.createRefreshToken(100L, "127.0.0.1", "UA")).thenReturn(Mono.just(refreshToken));

        StepVerifier.create(mfaAuthService.completeMfaLogin("some-mfa-token", "123456", "TOTP", "127.0.0.1", "UA"))
                .assertNext(resp -> {
                    assertThat(resp.getAccessToken()).isEqualTo("mfa-access-tok");
                    assertThat(resp.getRefreshToken()).isEqualTo("mfa-refresh-tok");
                })
                .verifyComplete();

        // MFA completion is a real login — it must land in the audit trail
        verify(auditService).logLoginSuccess(100L, "test@example.com", "127.0.0.1");
    }

    @Test
    @DisplayName("AUD19-LOGIN: Should NOT write LOGIN audit row when only the MFA challenge is issued")
    void loginWithRefreshToken_ShouldNotLogLoginAudit_WhenMfaChallengeIssued() {
        User mfaUser = User.builder()
                .id(200L).email("test@example.com").name("Test User")
                .passwordHash("$2a$10$hashedpassword")
                .role("ADMIN").active(true).mfaEnabled(true)
                .mfaPreferredMethod("TOTP").build();
        LoginRequest request = new LoginRequest("test@example.com", "password123", false, null);

        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(false));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(mfaUser));
        when(passwordEncoder.matches("password123", mfaUser.getPasswordHash())).thenReturn(true);
        when(loginAttemptService.clearFailedAttempts("test@example.com")).thenReturn(Mono.empty());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.set(startsWith("mfa:token:"), eq("200"), any())).thenReturn(Mono.just(true));

        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1", "UA"))
                .assertNext(resp -> {
                    assertThat(resp.getMfaRequired()).isTrue();
                    assertThat(resp.getMfaToken()).isNotBlank();
                    // AUD19C-MFAMETHOD: the challenge tells the FE which form to show
                    assertThat(resp.getMfaMethod()).isEqualTo("TOTP");
                    assertThat(resp.getAccessToken()).isNull();
                })
                .verifyComplete();

        // A challenge is not a login: the LOGIN row is written only after OTP verification
        verify(auditService, never()).logLoginSuccess(anyLong(), anyString(), any());
        verify(refreshTokenService, never()).createRefreshToken(anyLong(), any(), any());
    }

    @Test
    @DisplayName("Should register new user successfully")
    void register_ShouldCreateUserAndReturnToken() {
        RegisterRequest request = new RegisterRequest("New User", "new@example.com", "Password123!@", true, null);
        User savedUser = User.builder()
                .id(555L).name("New User").email("new@example.com")
                .passwordHash("hashed").role("VIEWER").active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        RefreshToken refreshToken = RefreshToken.builder()
                .id(10L).userId(555L).token("reg-refresh-tok").build();

        when(userRepository.existsByEmail("new@example.com")).thenReturn(Mono.just(false));
        when(idService.nextId()).thenReturn(555L);
        when(passwordEncoder.encode("Password123!@")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(savedUser));
        when(tokenProvider.generateToken(555L, "VIEWER")).thenReturn("access-tok");
        when(emailService.sendRegistrationWelcome("new@example.com", "New User")).thenReturn(Mono.empty());
        when(refreshTokenService.createRefreshToken(555L)).thenReturn(Mono.just(refreshToken));
        when(emailVerificationService.sendVerification(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(authService.register(request, "127.0.0.1"))
                .assertNext(resp -> {
                    assertThat(resp.getAccessToken()).isEqualTo("access-tok");
                    assertThat(resp.getRefreshToken()).isEqualTo("reg-refresh-tok");
                    assertThat(resp.getEmail()).isEqualTo("new@example.com");
                    assertThat(resp.getName()).isEqualTo("New User");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should send verification email on registration")
    void registerSendsVerificationEmail() {
        RegisterRequest request = new RegisterRequest("New User", "new@example.com", "Password123!@", true, null);
        User savedUser = User.builder()
                .id(555L).name("New User").email("new@example.com")
                .passwordHash("hashed").role("VIEWER").active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        RefreshToken refreshToken = RefreshToken.builder()
                .id(10L).userId(555L).token("reg-refresh-tok").build();

        when(userRepository.existsByEmail("new@example.com")).thenReturn(Mono.just(false));
        when(idService.nextId()).thenReturn(555L);
        when(passwordEncoder.encode("Password123!@")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(savedUser));
        when(tokenProvider.generateToken(555L, "VIEWER")).thenReturn("access-tok");
        when(emailService.sendRegistrationWelcome("new@example.com", "New User")).thenReturn(Mono.empty());
        when(refreshTokenService.createRefreshToken(555L)).thenReturn(Mono.just(refreshToken));
        when(emailVerificationService.sendVerification(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(authService.register(request, "127.0.0.1"))
                .expectNextCount(1).verifyComplete();

        verify(emailVerificationService).sendVerification("new@example.com");
    }

    @Test
    @DisplayName("Should register even when verification email fails")
    void registerSucceedsEvenIfVerificationEmailFails() {
        // The send happens OUTSIDE the transaction and is best-effort: the account
        // must not fail to be created because SMTP went down.
        RegisterRequest request = new RegisterRequest("New User", "new@example.com", "Password123!@", true, null);
        User savedUser = User.builder()
                .id(555L).name("New User").email("new@example.com")
                .passwordHash("hashed").role("VIEWER").active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        RefreshToken refreshToken = RefreshToken.builder()
                .id(10L).userId(555L).token("reg-refresh-tok").build();

        when(userRepository.existsByEmail("new@example.com")).thenReturn(Mono.just(false));
        when(idService.nextId()).thenReturn(555L);
        when(passwordEncoder.encode("Password123!@")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(savedUser));
        when(tokenProvider.generateToken(555L, "VIEWER")).thenReturn("access-tok");
        when(emailService.sendRegistrationWelcome("new@example.com", "New User")).thenReturn(Mono.empty());
        when(refreshTokenService.createRefreshToken(555L)).thenReturn(Mono.just(refreshToken));
        when(emailVerificationService.sendVerification(anyString()))
                .thenReturn(Mono.error(new RuntimeException("smtp down")));

        StepVerifier.create(authService.register(request, "127.0.0.1"))
                .expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("Should error with DuplicateResourceException + send notification email when address already exists")
    void register_ShouldThrow_WhenEmailAlreadyRegistered() {
        RegisterRequest request = new RegisterRequest("User", "existing@example.com", "Password123!@", true, null);
        when(userRepository.existsByEmail("existing@example.com")).thenReturn(Mono.just(true));
        when(emailService.sendTextEmail(eq("existing@example.com"), anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(passwordEncoder.encode("Password123!@")).thenReturn("hashed-dummy");

        StepVerifier.create(authService.register(request, "127.0.0.1"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(dev.catananti.exception.DuplicateResourceException.class);
                    assertThat(err.getMessage()).isEqualTo("error.email_already_registered");
                })
                .verify();

        verify(emailService).sendTextEmail(eq("existing@example.com"), anyString(), anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should logout with both tokens")
    void logout_ShouldRevokeTokens() {
        when(tokenProvider.getJtiFromToken("access-tok")).thenReturn("jti-123");
        when(tokenProvider.getRemainingLifetimeMs("access-tok")).thenReturn(3600000L);
        when(tokenBlacklistService.blacklist("jti-123", 3600000L)).thenReturn(Mono.just(true));
        when(refreshTokenService.revokeToken("refresh-tok")).thenReturn(Mono.empty());

        StepVerifier.create(authService.logout("refresh-tok", "access-tok"))
                .verifyComplete();

        verify(refreshTokenService).revokeToken("refresh-tok");
    }

    @Test
    @DisplayName("Should logout with null tokens gracefully")
    void logout_ShouldCompleteWithNullTokens() {
        StepVerifier.create(authService.logout(null, null))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate token returns false for invalid")
    void validateToken_ShouldReturnFalse_WhenTokenInvalid() {
        when(tokenProvider.validateToken("bad-token")).thenReturn(false);
        assertThat(authService.validateToken("bad-token")).isFalse();
    }
}
