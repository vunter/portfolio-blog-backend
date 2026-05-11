package dev.catananti.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey key;
    private JwtParser jwtParser;

    /**
     * Minimum required secret length for HS512 algorithm (64 bytes = 512 bits)
     */
    private static final int MIN_SECRET_LENGTH = 64;

    @PostConstruct
    public void init() {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    String.format("JWT secret must be at least %d characters for HS512 algorithm. " +
                            "Current length: %d. Please configure a secure jwt.secret property.",
                            MIN_SECRET_LENGTH, 
                            secret == null ? 0 : secret.length()));
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.jwtParser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer("portfolio-blog")
                .requireAudience("portfolio-blog-api")
                .build();
        log.info("JWT token provider initialized with HS512 algorithm");
    }

    /**
     * Generate a signed JWT for the given user.
     *
     * <p>Standard claims:
     * <ul>
     *   <li>{@code sub} — opaque user ID (Long), avoiding PII exposure in the token payload</li>
     *   <li>{@code role} — authorization role, validated against a server-side whitelist on use</li>
     *   <li>{@code iss} / {@code aud} — issuer/audience, enforced by the parser at validation time</li>
     *   <li>{@code iat} / {@code exp} — issued-at and immutable expiration (seconds since epoch)</li>
     *   <li>{@code jti} — random UUID for revocation via blacklist</li>
     *   <li>{@code auth_time} — original authentication timestamp (matches {@code iat} on first issue;
     *       carries forward across refresh rotations to support future step-up auth flows)</li>
     * </ul>
     */
    public String generateToken(Long userId, String role) {
        return generateToken(userId, role, Instant.now());
    }

    /**
     * Internal generator that allows preserving an original {@code auth_time} across refresh-rotated tokens.
     */
    public String generateToken(Long userId, String role, Instant authTime) {
        log.debug("Generated JWT token for userId={}", userId);
        var now = Instant.now();
        var expiryDate = now.plusMillis(expiration);

        return Jwts.builder()
                .header().type("JWT").and()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("role", role)
                .claim("auth_time", authTime.getEpochSecond())
                .issuer("portfolio-blog")
                .audience().add("portfolio-blog-api").and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiryDate))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }

    /**
     * Result of JWT token validation with granular error reporting.
     * Distinguishes between expired tokens (expected lifecycle) and invalid tokens (potential attack).
     */
    public record TokenValidationResult(boolean valid, boolean expired, Claims claims, String error) {
        public static TokenValidationResult success(Claims claims) {
            return new TokenValidationResult(true, false, claims, null);
        }
        public static TokenValidationResult expired(String message) {
            return new TokenValidationResult(false, true, null, message);
        }
        public static TokenValidationResult invalid(String message) {
            return new TokenValidationResult(false, false, null, message);
        }
    }

    /**
     * F-041: Validates and parses in a single pass — no re-parsing needed.
     * Checks signature integrity (HS512), encoding, structure, and expiration in a single operation.
     * Returns a detailed result indicating success, expiration, or the specific validation failure.
     */
    public TokenValidationResult validateAndParseClaims(String token) {
        try {
            Claims claims = jwtParser.parseSignedClaims(token).getPayload();
            return TokenValidationResult.success(claims);
        } catch (ExpiredJwtException e) {
            log.warn("JWT token expired: {}", e.getMessage());
            return TokenValidationResult.expired("Token expired");
        } catch (MalformedJwtException e) {
            log.warn("JWT token malformed — invalid encoding or structure: {}", e.getMessage());
            return TokenValidationResult.invalid("Malformed token");
        } catch (UnsupportedJwtException e) {
            log.warn("JWT token uses unsupported features: {}", e.getMessage());
            return TokenValidationResult.invalid("Unsupported token format");
        } catch (JwtException e) {
            log.warn("JWT validation failed — possible forgery or invalid secret: {}", e.getMessage());
            return TokenValidationResult.invalid("Invalid token");
        } catch (IllegalArgumentException e) {
            log.warn("JWT token is null or empty: {}", e.getMessage());
            return TokenValidationResult.invalid("Empty or null token");
        }
    }

    /**
     * Parse and validate token in a single operation, returning Claims if valid.
     * Backward-compatible wrapper around {@link #validateAndParseClaims(String)}.
     */
    public Optional<Claims> parseClaims(String token) {
        var result = validateAndParseClaims(token);
        return result.valid() ? Optional.ofNullable(result.claims()) : Optional.empty();
    }

    /**
     * Extract the user ID from the {@code sub} claim.
     * Returns {@code null} if the token is invalid or the subject is not a numeric ID.
     */
    public Long getUserIdFromToken(String token) {
        return parseClaims(token).map(Claims::getSubject).map(s -> {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                log.warn("JWT subject is not a numeric user id");
                return null;
            }
        }).orElse(null);
    }

    public String getRoleFromToken(String token) {
        return parseClaims(token).map(c -> c.get("role", String.class)).orElse(null);
    }

    /**
     * Extract the original authentication timestamp ({@code auth_time}, seconds since epoch).
     * Used by step-up auth flows to decide whether to require re-authentication.
     * Falls back to {@code iat} for tokens issued before {@code auth_time} was introduced.
     */
    public Instant getAuthTimeFromToken(String token) {
        return parseClaims(token).map(c -> {
            Long secs = c.get("auth_time", Long.class);
            if (secs != null) return Instant.ofEpochSecond(secs);
            var iat = c.getIssuedAt();
            return iat != null ? iat.toInstant() : null;
        }).orElse(null);
    }

    public boolean validateToken(String token) {
        return validateAndParseClaims(token).valid();
    }

    /**
     * Extract the JWT ID (jti) claim from a valid token.
     */
    public String getJtiFromToken(String token) {
        return parseClaims(token).map(Claims::getId).orElse(null);
    }

    /**
     * Get the remaining lifetime in milliseconds for a valid token.
     * Returns 0 if the token is invalid or already expired.
     */
    public long getRemainingLifetimeMs(String token) {
        return parseClaims(token)
                .map(claims -> {
                    var expiryInstant = claims.getExpiration().toInstant();
                    long remaining = expiryInstant.toEpochMilli() - Instant.now().toEpochMilli();
                    return Math.max(0, remaining);
                })
                .orElse(0L);
    }
}
