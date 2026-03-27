package dev.catananti.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Validates critical configuration properties on startup.
 * <p>
 * In production-like profiles ({@code prod}, {@code cloud}, {@code cluster}, {@code nitro}),
 * missing or insecure settings cause the application to fail-fast with a clear error message.
 * In development profiles, validation issues are logged as warnings.
 */
@Component
@Slf4j
public class ConfigurationValidator {

    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "cloud", "cluster", "nitro");
    private static final int MIN_JWT_SECRET_LENGTH = 64;

    private final Environment environment;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${app.storage.s3.access-key:}")
    private String s3AccessKey;

    @Value("${app.storage.s3.secret-key:}")
    private String s3SecretKey;

    @Value("${spring.r2dbc.password:}")
    private String dbPassword;

    public ConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        List<String> errors = new ArrayList<>();
        boolean isProduction = isProductionProfile();

        // JWT secret is always required and must be strong
        if (jwtSecret == null || jwtSecret.isBlank()) {
            errors.add("jwt.secret is not configured");
        } else if (jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
            errors.add("jwt.secret must be at least " + MIN_JWT_SECRET_LENGTH + " characters");
        }

        if (isProduction) {
            // S3 credentials required in production
            if (s3AccessKey == null || s3AccessKey.isBlank() || "minioadmin".equals(s3AccessKey)) {
                errors.add("app.storage.s3.access-key is not configured or using default MinIO credentials");
            }
            if (s3SecretKey == null || s3SecretKey.isBlank() || "minioadmin".equals(s3SecretKey)) {
                errors.add("app.storage.s3.secret-key is not configured or using default MinIO credentials");
            }

            // Database password required in production
            if (dbPassword == null || dbPassword.isBlank()) {
                errors.add("Database password is not configured");
            }
        }

        if (!errors.isEmpty()) {
            String message = "Configuration validation failed:\n  - " + String.join("\n  - ", errors);
            if (isProduction) {
                throw new IllegalStateException(message);
            } else {
                log.warn("STARTUP WARNING: {}", message);
            }
        } else {
            log.info("Configuration validation passed for profile(s): {}",
                    String.join(", ", environment.getActiveProfiles()));
        }
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(PRODUCTION_PROFILES::contains);
    }
}
