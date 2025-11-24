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
}
