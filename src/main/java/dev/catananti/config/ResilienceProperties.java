package dev.catananti.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for {@code resilience.*} configuration.
 * Grouping the previous 14 {@code @Value} parameters into a record lets us
 * validate the shape at startup, makes refactors propertyName-aware in the
 * IDE, and is the canonical Spring Boot pattern for cohesive config blocks.
 */
@ConfigurationProperties("resilience")
public record ResilienceProperties(
        Database database,
        Redis redis,
        External external
) {

    public ResilienceProperties {
        if (database == null) database = Database.defaults();
        if (redis == null) redis = Redis.defaults();
        if (external == null) external = External.defaults();
    }

    public record Database(
            int timeoutSeconds,
            int retryMaxAttempts,
            int retryMinBackoffMs,
            int retryMaxBackoffMs,
            int cbFailureRate,
            int cbWaitOpenSeconds,
            int cbSlidingWindowSize,
            int cbMinCalls
    ) {
        public static Database defaults() {
            return new Database(10, 3, 100, 1000, 50, 30, 10, 5);
        }
    }

    public record Redis(int timeoutSeconds) {
        public static Redis defaults() {
            return new Redis(5);
        }
    }

    public record External(
            int timeoutSeconds,
            int cbFailureRate,
            int cbWaitOpenSeconds,
            int cbSlidingWindowSize,
            int cbMinCalls
    ) {
        public static External defaults() {
            return new External(30, 50, 60, 10, 3);
        }
    }
}
