package dev.catananti.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AesEncryptor")
class AesEncryptorTest {

    // 32-byte key as 64 hex chars
    private static final String HEX_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private Environment devEnvironment() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});
        return env;
    }

    private Environment prodEnvironment() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        return env;
    }

    @Nested
    @DisplayName("encrypt and decrypt")
    class EncryptDecrypt {

        @Test
        @DisplayName("should encrypt and decrypt correctly with hex key")
        void shouldEncryptDecryptWithHexKey() {
            AesEncryptor encryptor = new AesEncryptor(HEX_KEY, devEnvironment());

            String plaintext = "JBSWY3DPEHPK3PXP";
            String encrypted = encryptor.encrypt(plaintext);
            String decrypted = encryptor.decrypt(encrypted);

            assertThat(encrypted).isNotEqualTo(plaintext);
            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("should produce different ciphertext each time (random IV)")
        void shouldProduceDifferentCiphertextEachTime() {
            AesEncryptor encryptor = new AesEncryptor(HEX_KEY, devEnvironment());

            String plaintext = "same-secret";
            String encrypted1 = encryptor.encrypt(plaintext);
            String encrypted2 = encryptor.encrypt(plaintext);

            assertThat(encrypted1).isNotEqualTo(encrypted2);
            assertThat(encryptor.decrypt(encrypted1)).isEqualTo(plaintext);
            assertThat(encryptor.decrypt(encrypted2)).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            AesEncryptor encryptor = new AesEncryptor(HEX_KEY, devEnvironment());

            assertThat(encryptor.encrypt(null)).isNull();
            assertThat(encryptor.decrypt(null)).isNull();
        }

        @Test
        @DisplayName("should handle empty string")
        void shouldHandleEmptyString() {
            AesEncryptor encryptor = new AesEncryptor(HEX_KEY, devEnvironment());

            String encrypted = encryptor.encrypt("");
            assertThat(encryptor.decrypt(encrypted)).isEmpty();
        }

        @Test
        @DisplayName("should handle unicode characters")
        void shouldHandleUnicode() {
            AesEncryptor encryptor = new AesEncryptor(HEX_KEY, devEnvironment());

            String plaintext = "秘密のキー 🔐";
            String decrypted = encryptor.decrypt(encryptor.encrypt(plaintext));

            assertThat(decrypted).isEqualTo(plaintext);
        }
    }

    @Nested
    @DisplayName("tamper detection")
    class TamperDetection {

        @Test
        @DisplayName("should reject tampered ciphertext")
        void shouldRejectTamperedCiphertext() {
            AesEncryptor encryptor = new AesEncryptor(HEX_KEY, devEnvironment());

            String encrypted = encryptor.encrypt("secret");
            char[] chars = encrypted.toCharArray();
            chars[chars.length - 2] = chars[chars.length - 2] == 'A' ? 'B' : 'A';
            String tampered = new String(chars);

            assertThatThrownBy(() -> encryptor.decrypt(tampered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to decrypt");
        }
    }

    @Nested
    @DisplayName("no key configured (dev mode)")
    class NoKeyConfigured {

        @Test
        @DisplayName("should return plaintext when no key configured")
        void shouldReturnPlaintextWithoutKey() {
            AesEncryptor encryptor = new AesEncryptor("", devEnvironment());

            assertThat(encryptor.encrypt("secret")).isEqualTo("secret");
            assertThat(encryptor.decrypt("secret")).isEqualTo("secret");
        }

        @Test
        @DisplayName("should not throw in dev profile without key")
        void shouldNotThrowInDev() {
            AesEncryptor encryptor = new AesEncryptor("", devEnvironment());
            encryptor.validateConfiguration();
        }
    }

    @Nested
    @DisplayName("production validation")
    class ProductionValidation {

        @Test
        @DisplayName("should throw in prod profile without key")
        void shouldThrowInProdWithoutKey() {
            AesEncryptor encryptor = new AesEncryptor("", prodEnvironment());

            assertThatThrownBy(encryptor::validateConfiguration)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("required in production");
        }

        @Test
        @DisplayName("should not throw in prod profile with key")
        void shouldNotThrowInProdWithKey() {
            AesEncryptor encryptor = new AesEncryptor(HEX_KEY, prodEnvironment());
            encryptor.validateConfiguration();
        }

        @Test
        @DisplayName("should throw for cloud profile without key")
        void shouldThrowForCloudProfile() {
            Environment env = mock(Environment.class);
            when(env.getActiveProfiles()).thenReturn(new String[]{"cloud"});
            AesEncryptor encryptor = new AesEncryptor("", env);

            assertThatThrownBy(encryptor::validateConfiguration)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("key decoding")
    class KeyDecoding {

        @Test
        @DisplayName("should reject key with wrong length")
        void shouldRejectWrongLengthKey() {
            assertThatThrownBy(() -> new AesEncryptor("0123456789abcdef", devEnvironment()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32 bytes");
        }

        @Test
        @DisplayName("should accept Base64 key")
        void shouldAcceptBase64Key() {
            // 32 bytes as Base64
            String base64Key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
            AesEncryptor encryptor = new AesEncryptor(base64Key, devEnvironment());

            String encrypted = encryptor.encrypt("test");
            assertThat(encryptor.decrypt(encrypted)).isEqualTo("test");
        }
    }
}
