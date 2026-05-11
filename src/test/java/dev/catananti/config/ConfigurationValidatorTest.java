package dev.catananti.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ConfigurationValidator")
class ConfigurationValidatorTest {

    private ConfigurationValidator createValidator(String profile, String jwtSecret,
                                                    String s3AccessKey, String s3SecretKey,
                                                    String dbPassword) throws Exception {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{profile});

        ConfigurationValidator validator = new ConfigurationValidator(env);

        setField(validator, "jwtSecret", jwtSecret);
        setField(validator, "s3AccessKey", s3AccessKey);
        setField(validator, "s3SecretKey", s3SecretKey);
        setField(validator, "dbPassword", dbPassword);

        return validator;
    }

    private void setField(Object obj, String fieldName, String value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    @Nested
    @DisplayName("production profile")
    class ProductionProfile {

        @Test
        @DisplayName("should throw when JWT secret is missing in prod")
        void shouldThrowWhenJwtSecretMissing() throws Exception {
            ConfigurationValidator validator = createValidator("prod", "", "access", "secret", "dbpass");

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.secret is not configured");
        }

        @Test
        @DisplayName("should throw when JWT secret is too short in prod")
        void shouldThrowWhenJwtSecretTooShort() throws Exception {
            ConfigurationValidator validator = createValidator("prod", "short-secret", "access", "secret", "dbpass");

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 64 characters");
        }

        @Test
        @DisplayName("should throw when S3 access key uses MinIO defaults in prod")
        void shouldThrowWhenS3UsesMinioDefaults() throws Exception {
            String jwt = "a".repeat(64);
            ConfigurationValidator validator = createValidator("prod", jwt, "minioadmin", "minioadmin", "dbpass");

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("s3.access-key");
        }

        @Test
        @DisplayName("should throw when S3 secret key is blank in prod")
        void shouldThrowWhenS3SecretBlank() throws Exception {
            String jwt = "a".repeat(64);
            ConfigurationValidator validator = createValidator("prod", jwt, "real-access", "", "dbpass");

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("s3.secret-key");
        }

        @Test
        @DisplayName("should throw when DB password is missing in prod")
        void shouldThrowWhenDbPasswordMissing() throws Exception {
            String jwt = "a".repeat(64);
            ConfigurationValidator validator = createValidator("prod", jwt, "access", "secret", "");

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Database password");
        }

        @Test
        @DisplayName("should pass with all required config in prod")
        void shouldPassWithValidConfig() throws Exception {
            String jwt = "a".repeat(64);
            ConfigurationValidator validator = createValidator("prod", jwt, "access", "secret", "dbpass");

            assertThatNoException().isThrownBy(validator::validate);
        }

        @Test
        @DisplayName("should throw for cloud profile too")
        void shouldThrowForCloudProfile() throws Exception {
            ConfigurationValidator validator = createValidator("cloud", "", "access", "secret", "dbpass");

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("should collect multiple errors")
        void shouldCollectMultipleErrors() throws Exception {
            ConfigurationValidator validator = createValidator("prod", "", "", "", "");

            assertThatThrownBy(validator::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.secret")
                    .hasMessageContaining("s3.access-key")
                    .hasMessageContaining("Database password");
        }
    }

    @Nested
    @DisplayName("development profile")
    class DevelopmentProfile {

        @Test
        @DisplayName("should warn but not throw in dev with missing config")
        void shouldWarnButNotThrowInDev() throws Exception {
            ConfigurationValidator validator = createValidator("dev", "", "", "", "");

            assertThatNoException().isThrownBy(validator::validate);
        }

        @Test
        @DisplayName("should warn for short JWT secret in dev")
        void shouldWarnForShortJwtInDev() throws Exception {
            ConfigurationValidator validator = createValidator("dev", "short", "", "", "");

            assertThatNoException().isThrownBy(validator::validate);
        }

        @Test
        @DisplayName("should pass with valid config in dev")
        void shouldPassWithValidConfigInDev() throws Exception {
            String jwt = "a".repeat(64);
            ConfigurationValidator validator = createValidator("dev", jwt, "", "", "");

            assertThatNoException().isThrownBy(validator::validate);
        }
    }
}
