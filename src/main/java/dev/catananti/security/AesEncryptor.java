package dev.catananti.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryptor for encrypting TOTP secrets at rest.
 * The encryption key is injected from configuration (MFA_ENCRYPTION_KEY env var).
 * <p>
 * Format: Base64(IV[12] + ciphertext + tag[16])
 */
@Component
@Slf4j
public class AesEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // bits

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesEncryptor(@Value("${mfa.encryption-key:}") String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            log.warn("MFA_ENCRYPTION_KEY not configured — MFA TOTP secrets will NOT be encrypted. " +
                     "Set MFA_ENCRYPTION_KEY (32-byte hex or Base64) in production.");
            this.keySpec = null;
        } else {
            byte[] keyBytes = decodeKey(encryptionKey);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("MFA_ENCRYPTION_KEY must be exactly 32 bytes (256 bits), got " + keyBytes.length);
            }
            this.keySpec = new SecretKeySpec(keyBytes, "AES");
        }
    }

    /**
     * Encrypt plaintext. If no key is configured, returns plaintext as-is (dev mode).
     */
    public String encrypt(String plaintext) {
        if (keySpec == null || plaintext == null) return plaintext;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes());

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt MFA secret", e);
        }
    }

    /**
     * Decrypt ciphertext. If no key is configured, returns ciphertext as-is (dev mode).
     */
    public String decrypt(String ciphertext) {
        if (keySpec == null || ciphertext == null) return ciphertext;
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt MFA secret", e);
        }
    }

    private byte[] decodeKey(String key) {
        // Try hex first (64 hex chars = 32 bytes)
        if (key.length() == 64 && key.matches("[0-9a-fA-F]+")) {
            byte[] bytes = new byte[32];
            for (int i = 0; i < 32; i++) {
                bytes[i] = (byte) Integer.parseInt(key.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        }
        // Try Base64
        return Base64.getDecoder().decode(key);
    }
}
