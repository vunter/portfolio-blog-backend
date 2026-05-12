package dev.catananti.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Type-safe binding for {@code resilience.*} configuration.
 * Grouping the previous 14 {@code @Value} parameters into a record lets us
 * validate the shape at startup, makes refactors propertyName-aware in the
 * IDE, and is the canonical Spring Boot pattern for cohesive config blocks.
 *
 * <p>The {@link DefaultValue} annotation on each component supplies the
 * value Spring binds when the property is absent. The previous compact-
 * constructor null-check only ran when the whole group was missing; if any
 * property was set, the rest were bound to {@code 0}, which crashes
 * Resilience4j's circuit-breaker builder.</p>
 */
@ConfigurationProperties("resilience")
public record ResilienceProperties(
        @DefaultValue Database database,
        @DefaultValue Redis redis,
        @DefaultValue External external
) {

    public record Database(
            @DefaultValue("10") int timeoutSeconds,
            @DefaultValue("3") int retryMaxAttempts,
            @DefaultValue("100") int retryMinBackoffMs,
            @DefaultValue("1000") int retryMaxBackoffMs,
            @DefaultValue("50") int cbFailureRate,
            @DefaultValue("30") int cbWaitOpenSeconds,
            @DefaultValue("10") int cbSlidingWindowSize,
            @DefaultValue("5") int cbMinCalls
    ) {}

    public record Redis(@DefaultValue("5") int timeoutSeconds) {}

    public record External(
            @DefaultValue("30") int timeoutSeconds,
            @DefaultValue("50") int cbFailureRate,
            @DefaultValue("60") int cbWaitOpenSeconds,
            @DefaultValue("10") int cbSlidingWindowSize,
            @DefaultValue("3") int cbMinCalls
    ) {}
}
