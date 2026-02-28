package dev.catananti.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Centralized SHA-256 hashing utility.
 * Eliminates duplicated {@code MessageDigest.getInstance("SHA-256")} patterns
 * scattered across multiple services.
 */
public final class DigestUtils {

    private static final HexFormat HEX = HexFormat.of();

    private DigestUtils() {
        // utility class
    }

    /**
     * Compute SHA-256 hash of the input and return the full hex string.
     *
     * @param input text to hash
     * @return lowercase hex-encoded SHA-256 digest
     */
    public static String sha256Hex(String input) {
        Objects.requireNonNull(input, "Input must not be null");
        byte[] hash = sha256(input.getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(hash);
    }

    /**
     * Compute SHA-256 hash and return only the first {@code length} hex characters.
     *
     * @param input  text to hash
     * @param length number of hex characters to return
     * @return truncated hex-encoded SHA-256 digest
     */
    public static String sha256Hex(String input, int length) {
        Objects.requireNonNull(input, "Input must not be null");
        return sha256Hex(input).substring(0, length);
    }

    /**
     * Compute raw SHA-256 hash bytes.
     *
     * @param data bytes to hash
     * @return SHA-256 digest bytes
     */
    public static byte[] sha256(byte[] data) {
        Objects.requireNonNull(data, "Input must not be null");
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK spec
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Constant-time comparison of two strings to prevent timing attacks.
     * Uses MessageDigest.isEqual on the UTF-8 bytes of both strings.
     *
     * @param a first string
     * @param b second string
     * @return true if both strings are equal
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * F-291: Escape SQL LIKE special characters (%, _, \) in user-supplied search terms.
     * This prevents users from injecting wildcards into LIKE patterns.
     *
     * @param input raw user search query
     * @return escaped string safe for use inside LIKE patterns
     */
    public static String escapeLikePattern(String input) {
        if (input == null) return null;
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * F-216: Sanitize a user-supplied URL by rejecting dangerous schemes.
     * Only allows http, https, and mailto schemes. Returns null for any
     * URL using javascript:, data:, vbscript:, or other dangerous schemes.
     *
     * @param url the user-supplied URL
     * @return the URL unchanged if safe, or null if it uses a dangerous scheme
     */
    public static String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) return url;
        String trimmed = url.strip();
        // Collapse embedded whitespace/control chars that could bypass scheme detection
        String normalized = trimmed.replaceAll("[\\s\\u0000-\\u001F]+", "");
        String lower = normalized.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
            return trimmed;
        }
        // URLs without a scheme are treated as relative (safe)
        if (!lower.contains(":")) {
            return trimmed;
        }
        // Any other scheme (javascript:, data:, vbscript:, etc.) is rejected
        return null;
    }
}
