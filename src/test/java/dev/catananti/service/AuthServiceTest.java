package dev.catananti.service;

import dev.catananti.dto.AuthResponse;
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

    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, passwordEncoder, tokenProvider,
                refreshTokenService, messageSource, idService,
                htmlSanitizerService, tokenBlacklistService, emailService,
                loginAttemptService);
        // Set jwtExpirationMs via reflection
        try {
            var field = AuthService.class.getDeclaredField("jwtExpirationMs");
            field.setAccessible(true);
            field.set(authService, 86400000L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        lenient().when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenReturn("Invalid credentials");
        lenient().when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));

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

    @Test
    @DisplayName("Should login successfully with valid credentials")
    void login_ShouldReturnToken_WhenCredentialsValid() {
        // Given
        LoginRequest request = new LoginRequest("test@example.com", "password123", false, null);
        
        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(false));
        when(loginAttemptService.clearFailedAttempts("test@example.com")).thenReturn(Mono.empty());
        when(userRepository.findByEmail("test@example.com"))
                .thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("password123", testUser.getPasswordHash()))
                .thenReturn(true);
        when(tokenProvider.generateToken("test@example.com", "ADMIN"))
                .thenReturn("jwt-token-here");

        // When
        Mono<AuthResponse> result = authService.login(request, "127.0.0.1");

        // Then
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getToken()).isEqualTo("jwt-token-here");
                    assertThat(response.getType()).isEqualTo("Bearer");
                    assertThat(response.getEmail()).isEqualTo("test@example.com");
                    assertThat(response.getName()).isEqualTo("Test User");
                })
                .verifyComplete();

        verify(userRepository).findByEmail("test@example.com");
        verify(passwordEncoder).matches("password123", testUser.getPasswordHash());
        verify(tokenProvider).generateToken("test@example.com", "ADMIN");
        verify(loginAttemptService).clearFailedAttempts("test@example.com");
    }

    @Test
    @DisplayName("Should throw BadCredentialsException when user not found")
    void login_ShouldThrow_WhenUserNotFound() {
        // Given
        LoginRequest request = new LoginRequest("nonexistent@example.com", "password", false, null);
        
        when(loginAttemptService.isBlocked("nonexistent@example.com")).thenReturn(Mono.just(false));
        when(loginAttemptService.recordFailedAttempt(eq("nonexistent@example.com"), anyString())).thenReturn(Mono.just(1));
        when(userRepository.findByEmail("nonexistent@example.com"))
                .thenReturn(Mono.empty());

        // When
        Mono<AuthResponse> result = authService.login(request, "127.0.0.1");

        // Then
        StepVerifier.create(result)
                .expectError(BadCredentialsException.class)
                .verify();
        
        verify(loginAttemptService).recordFailedAttempt(eq("nonexistent@example.com"), anyString());
    }

    @Test
    @DisplayName("Should throw BadCredentialsException when password is wrong")
    void login_ShouldThrow_WhenPasswordWrong() {
        // Given
        LoginRequest request = new LoginRequest("test@example.com", "wrongpassword", false, null);
        
        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(false));
        when(loginAttemptService.recordFailedAttempt(eq("test@example.com"), anyString())).thenReturn(Mono.just(1));
        when(loginAttemptService.getRemainingAttempts("test@example.com")).thenReturn(Mono.just(4));
        when(userRepository.findByEmail("test@example.com"))
                .thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("wrongpassword", testUser.getPasswordHash()))
                .thenReturn(false);

        // When
        Mono<AuthResponse> result = authService.login(request, "127.0.0.1");

        // Then
        StepVerifier.create(result)
                .expectError(BadCredentialsException.class)
                .verify();
        
        verify(loginAttemptService).recordFailedAttempt(eq("test@example.com"), anyString());
    }

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

    @Test
    @DisplayName("Should return email from token")
    void getEmailFromToken_ShouldReturnEmail() {
        // Given
        String token = "jwt-token";
        when(tokenProvider.getEmailFromToken(token)).thenReturn("test@example.com");

        // When
        String email = authService.getEmailFromToken(token);

        // Then
        assertThat(email).isEqualTo("test@example.com");
        verify(tokenProvider).getEmailFromToken(token);
    }

    // ==================== ADDED TESTS ====================

    @Test
    @DisplayName("Should throw AccountLockedException when account is blocked")
    void login_ShouldThrow_WhenAccountLocked() {
        LoginRequest request = new LoginRequest("test@example.com", "pass", false, null);
        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(true));
        when(loginAttemptService.getRemainingLockoutTime("test@example.com")).thenReturn(Mono.just(300000L));

        StepVerifier.create(authService.login(request, "127.0.0.1"))
                .expectError(AccountLockedException.class)
                .verify();
    }

    @Test
    @DisplayName("Should lock account when remaining attempts reach 0")
    void login_ShouldLockAccount_WhenNoRemainingAttempts() {
        LoginRequest request = new LoginRequest("test@example.com", "wrongpass", false, null);
        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(false));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(testUser));
        when(passwordEncoder.matches("wrongpass", testUser.getPasswordHash())).thenReturn(false);
        when(loginAttemptService.recordFailedAttempt(eq("test@example.com"), anyString())).thenReturn(Mono.just(5));
        when(loginAttemptService.getRemainingAttempts("test@example.com")).thenReturn(Mono.just(0));

        StepVerifier.create(authService.login(request, "127.0.0.1"))
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
        when(tokenProvider.generateToken("test@example.com", "ADMIN")).thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(testUser.getId())).thenReturn(Mono.just(refreshToken));

        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1"))
                .assertNext(resp -> {
                    assertThat(resp.getAccessToken()).isEqualTo("access-token");
                    assertThat(resp.getRefreshToken()).isEqualTo("refresh-tok");
                    assertThat(resp.getTokenType()).isEqualTo("Bearer");
                    assertThat(resp.getEmail()).isEqualTo("test@example.com");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw when loginWithRefreshToken account is locked")
    void loginWithRefreshToken_ShouldThrow_WhenLocked() {
        LoginRequest request = new LoginRequest("test@example.com", "pass", false, null);
        when(loginAttemptService.isBlocked("test@example.com")).thenReturn(Mono.just(true));
        when(loginAttemptService.getRemainingLockoutTime("test@example.com")).thenReturn(Mono.just(120000L));

        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1"))
                .expectError(AccountLockedException.class)
                .verify();
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

        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1"))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    @Test
    @DisplayName("Should throw when loginWithRefreshToken user not found")
    void loginWithRefreshToken_ShouldThrow_WhenUserNotFound() {
        LoginRequest request = new LoginRequest("ghost@example.com", "pass", false, null);
        when(loginAttemptService.isBlocked("ghost@example.com")).thenReturn(Mono.just(false));
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Mono.empty());
        when(loginAttemptService.recordFailedAttempt(eq("ghost@example.com"), anyString())).thenReturn(Mono.just(1));

        StepVerifier.create(authService.loginWithRefreshToken(request, "127.0.0.1"))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    @Test
    @DisplayName("Should refresh access token successfully")
    void refreshAccessToken_ShouldReturnNewTokens() {
        RefreshToken newRefreshToken = RefreshToken.builder()
                .id(2L).userId(testUser.getId()).token("new-refresh-tok").build();

        when(refreshTokenService.verifyAndRotate("old-refresh-tok")).thenReturn(Mono.just(newRefreshToken));
        when(userRepository.findById(testUser.getId())).thenReturn(Mono.just(testUser));
        when(tokenProvider.generateToken("test@example.com", "ADMIN")).thenReturn("new-access-token");

        StepVerifier.create(authService.refreshAccessToken("old-refresh-tok"))
                .assertNext(resp -> {
                    assertThat(resp.getAccessToken()).isEqualTo("new-access-token");
                    assertThat(resp.getRefreshToken()).isEqualTo("new-refresh-tok");
                    assertThat(resp.getEmail()).isEqualTo("test@example.com");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw when user not found during refresh")
    void refreshAccessToken_ShouldThrow_WhenUserNotFound() {
        RefreshToken newRefreshToken = RefreshToken.builder()
                .id(2L).userId(999L).token("tok").build();

        when(refreshTokenService.verifyAndRotate("tok")).thenReturn(Mono.just(newRefreshToken));
        when(userRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(authService.refreshAccessToken("tok"))
                .expectError(SecurityException.class)
                .verify();
    }

    @Test
    @DisplayName("Should register new user successfully")
    void register_ShouldCreateUserAndReturnToken() {
        RegisterRequest request = new RegisterRequest("New User", "new@example.com", "Password123!@", null);
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
        when(tokenProvider.generateToken("new@example.com", "VIEWER")).thenReturn("access-tok");
        when(emailService.sendRegistrationWelcome("new@example.com", "New User")).thenReturn(Mono.empty());
        when(refreshTokenService.createRefreshToken(555L)).thenReturn(Mono.just(refreshToken));

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
    @DisplayName("Should reject registration when email already exists")
    void register_ShouldThrow_WhenEmailAlreadyRegistered() {
        RegisterRequest request = new RegisterRequest("User", "existing@example.com", "Password123!@", null);
        when(userRepository.existsByEmail("existing@example.com")).thenReturn(Mono.just(true));

        StepVerifier.create(authService.register(request, "127.0.0.1"))
                .expectError(ResponseStatusException.class)
                .verify();
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
