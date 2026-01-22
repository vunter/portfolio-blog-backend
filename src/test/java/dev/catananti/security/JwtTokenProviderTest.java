package dev.catananti.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;
    private static final String SECRET = "this-is-a-very-long-secret-key-that-is-at-least-256-bits-long-for-hs512";
    private static final long EXPIRATION = 86400000L; // 24 hours

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(tokenProvider, "secret", SECRET);
        ReflectionTestUtils.setField(tokenProvider, "expiration", EXPIRATION);
        tokenProvider.init();
    }

    @Test
    @DisplayName("Should generate valid JWT token")
    void generateToken_ShouldCreateValidToken() {
        // When
        String token = tokenProvider.generateToken("test@example.com", "ADMIN");

        // Then
        assertThat(token).isNotNull();
        assertThat(token).isNotEmpty();
        assertThat(token.split("\\.")).hasSize(3); // JWT has 3 parts
    }

    @Test
    @DisplayName("Should extract email from token")
    void getEmailFromToken_ShouldReturnEmail() {
        // Given
        String token = tokenProvider.generateToken("test@example.com", "ADMIN");

        // When
        String email = tokenProvider.getEmailFromToken(token);

        // Then
        assertThat(email).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("Should extract role from token")
    void getRoleFromToken_ShouldReturnRole() {
        // Given
        String token = tokenProvider.generateToken("test@example.com", "ADMIN");

        // When
        String role = tokenProvider.getRoleFromToken(token);

        // Then
        assertThat(role).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Should validate valid token")
    void validateToken_ShouldReturnTrue_WhenTokenValid() {
        // Given
        String token = tokenProvider.generateToken("test@example.com", "ADMIN");

        // When
        boolean isValid = tokenProvider.validateToken(token);

        // Then
        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("Should invalidate expired token")
    void validateToken_ShouldReturnFalse_WhenTokenExpired() {
        // Given - Create an expired token manually
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expiredToken = Jwts.builder()
                .subject("test@example.com")
                .claim("role", "ADMIN")
                .issuedAt(new Date(System.currentTimeMillis() - 100000))
                .expiration(new Date(System.currentTimeMillis() - 50000)) // Already expired
                .signWith(key, Jwts.SIG.HS512)
                .compact();

        // When
        boolean isValid = tokenProvider.validateToken(expiredToken);

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("Should invalidate malformed token")
    void validateToken_ShouldReturnFalse_WhenTokenMalformed() {
        // When
        boolean isValid = tokenProvider.validateToken("invalid.token.here");

        // Then
        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("Should invalidate null token")
    void validateToken_ShouldReturnFalse_WhenTokenNull() {
        // When
        boolean isValid = tokenProvider.validateToken(null);

        // Then
        assertThat(isValid).isFalse();
    }

    // ==================== Additional Coverage Tests ====================

    @Test
    @DisplayName("Should extract JTI from valid token")
    void getJtiFromToken_ShouldReturnJti() {
        String token = tokenProvider.generateToken("test@example.com", "ADMIN");
        String jti = tokenProvider.getJtiFromToken(token);
        assertThat(jti).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("Should return null JTI from invalid token")
    void getJtiFromToken_ShouldReturnNull_WhenTokenInvalid() {
        String jti = tokenProvider.getJtiFromToken("invalid.token.here");
        assertThat(jti).isNull();
    }

    @Test
    @DisplayName("Should return positive remaining lifetime for valid token")
    void getRemainingLifetimeMs_ShouldReturnPositive_WhenTokenValid() {
        String token = tokenProvider.generateToken("test@example.com", "ADMIN");
        long remaining = tokenProvider.getRemainingLifetimeMs(token);
        assertThat(remaining).isGreaterThan(0);
        assertThat(remaining).isLessThanOrEqualTo(EXPIRATION);
    }

    @Test
    @DisplayName("Should return zero remaining lifetime for expired token")
    void getRemainingLifetimeMs_ShouldReturnZero_WhenTokenExpired() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expiredToken = Jwts.builder()
                .subject("test@example.com")
                .issuedAt(new Date(System.currentTimeMillis() - 100000))
                .expiration(new Date(System.currentTimeMillis() - 50000))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
        long remaining = tokenProvider.getRemainingLifetimeMs(expiredToken);
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("Should return zero remaining lifetime for invalid token")
    void getRemainingLifetimeMs_ShouldReturnZero_WhenTokenInvalid() {
        long remaining = tokenProvider.getRemainingLifetimeMs("completely.invalid.token");
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("Should parse claims from valid token")
    void parseClaims_ShouldReturnClaims_WhenTokenValid() {
        String token = tokenProvider.generateToken("test@example.com", "ADMIN");
        var claims = tokenProvider.parseClaims(token);
        assertThat(claims).isPresent();
        assertThat(claims.get().getSubject()).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("Should return empty for parseClaims with expired token")
    void parseClaims_ShouldReturnEmpty_WhenTokenExpired() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expiredToken = Jwts.builder()
                .subject("test@example.com")
                .issuedAt(new Date(System.currentTimeMillis() - 100000))
                .expiration(new Date(System.currentTimeMillis() - 50000))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
        assertThat(tokenProvider.parseClaims(expiredToken)).isEmpty();
    }

    @Test
    @DisplayName("Should return expired result for expired token")
    void validateAndParseClaims_ShouldReturnExpired() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expiredToken = Jwts.builder()
                .subject("test@example.com")
                .issuedAt(new Date(System.currentTimeMillis() - 100000))
                .expiration(new Date(System.currentTimeMillis() - 50000))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
        var result = tokenProvider.validateAndParseClaims(expiredToken);
        assertThat(result.valid()).isFalse();
        assertThat(result.expired()).isTrue();
        assertThat(result.error()).isEqualTo("Token expired");
        assertThat(result.claims()).isNull();
    }

    @Test
    @DisplayName("Should return invalid for token signed with different key")
    void validateAndParseClaims_ShouldReturnInvalid_WhenDifferentKey() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-very-long-secret-key-that-is-at-least-64-characters-for-hs512!!"
                        .getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("test@example.com")
                .signWith(otherKey, Jwts.SIG.HS512)
                .compact();
        var result = tokenProvider.validateAndParseClaims(token);
        assertThat(result.valid()).isFalse();
        assertThat(result.expired()).isFalse();
    }

    @Test
    @DisplayName("Should return invalid for empty token")
    void validateAndParseClaims_ShouldReturnInvalid_WhenEmpty() {
        var result = tokenProvider.validateAndParseClaims("");
        assertThat(result.valid()).isFalse();
    }

    @Test
    @DisplayName("Should return success result with claims for valid token")
    void validateAndParseClaims_ShouldReturnSuccess_WhenValid() {
        String token = tokenProvider.generateToken("test@example.com", "EDITOR");
        var result = tokenProvider.validateAndParseClaims(token);
        assertThat(result.valid()).isTrue();
        assertThat(result.expired()).isFalse();
        assertThat(result.claims()).isNotNull();
        assertThat(result.claims().getSubject()).isEqualTo("test@example.com");
        assertThat(result.claims().get("role", String.class)).isEqualTo("EDITOR");
        assertThat(result.error()).isNull();
    }

    @Test
    @DisplayName("Should throw on init with null secret")
    void init_ShouldThrow_WhenSecretNull() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "secret", null);
        ReflectionTestUtils.setField(provider, "expiration", EXPIRATION);

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 64");
    }

    @Test
    @DisplayName("Should throw on init with short secret")
    void init_ShouldThrow_WhenSecretTooShort() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "secret", "too-short");
        ReflectionTestUtils.setField(provider, "expiration", EXPIRATION);

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 64");
    }

    @Test
    @DisplayName("Should return null email from invalid token")
    void getEmailFromToken_ShouldReturnNull_WhenInvalid() {
        assertThat(tokenProvider.getEmailFromToken("bad.token.here")).isNull();
    }

    @Test
    @DisplayName("Should return null role from invalid token")
    void getRoleFromToken_ShouldReturnNull_WhenInvalid() {
        assertThat(tokenProvider.getRoleFromToken("bad.token.here")).isNull();
    }

    @Test
    @DisplayName("Generated tokens should have unique JTIs")
    void generateToken_ShouldHaveUniqueJtis() {
        String token1 = tokenProvider.generateToken("test@example.com", "ADMIN");
        String token2 = tokenProvider.generateToken("test@example.com", "ADMIN");
        String jti1 = tokenProvider.getJtiFromToken(token1);
        String jti2 = tokenProvider.getJtiFromToken(token2);
        assertThat(jti1).isNotEqualTo(jti2);
    }
}
