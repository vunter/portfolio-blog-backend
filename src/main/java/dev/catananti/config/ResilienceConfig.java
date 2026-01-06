package dev.catananti.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Centralised resilience settings: timeouts and retry strategies.
 * Inject this component directly — no need for @Qualifier on Duration beans.
 *
 * <pre>
 * return articleRepository.findBySlug(slug)
 *         .timeout(resilience.databaseTimeout())
 *         .retryWhen(resilience.databaseRetry());
 * </pre>
 */
@Component
@Getter
@Slf4j
public class ResilienceConfig {

    private final Duration databaseTimeout;
    private final Duration redisTimeout;
    private final Duration externalTimeout;
    private final int databaseRetryMaxAttempts;
    private final Duration databaseRetryMinBackoff;
    private final Duration databaseRetryMaxBackoff;

    public ResilienceConfig(
            @Value("${resilience.database.timeout-seconds:10}") int databaseTimeoutSeconds,
            @Value("${resilience.database.retry-max-attempts:3}") int databaseRetryMaxAttempts,
            @Value("${resilience.database.retry-min-backoff-ms:100}") int databaseRetryMinBackoffMs,
            @Value("${resilience.database.retry-max-backoff-ms:1000}") int databaseRetryMaxBackoffMs,
            @Value("${resilience.redis.timeout-seconds:5}") int redisTimeoutSeconds,
            @Value("${resilience.external.timeout-seconds:30}") int externalTimeoutSeconds
    ) {
        this.databaseTimeout = Duration.ofSeconds(databaseTimeoutSeconds);
        this.redisTimeout = Duration.ofSeconds(redisTimeoutSeconds);
        this.externalTimeout = Duration.ofSeconds(externalTimeoutSeconds);
        this.databaseRetryMaxAttempts = databaseRetryMaxAttempts;
        this.databaseRetryMinBackoff = Duration.ofMillis(databaseRetryMinBackoffMs);
        this.databaseRetryMaxBackoff = Duration.ofMillis(databaseRetryMaxBackoffMs);
        log.info("Resilience configuration initialized");
    }

    /**
     * Retry strategy for transient database failures.
     * Uses exponential backoff with jitter to prevent thundering herd.
     */
    public Retry databaseRetry() {
        return Retry.backoff(databaseRetryMaxAttempts, databaseRetryMinBackoff)
                .maxBackoff(databaseRetryMaxBackoff)
                .jitter(0.5)
                .filter(this::isRetryableException)
                .doBeforeRetry(signal -> {
                    if (signal.totalRetries() > 0) {
                        log.warn("Retrying database operation, attempt {}/{}: {}",
                                signal.totalRetries() + 1,
                                databaseRetryMaxAttempts,
                                signal.failure().getMessage());
                    }
                });
    }

    /**
     * Determines if an exception is retryable (transient errors only).
     */
    private boolean isRetryableException(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null) return false;

        String lowerMessage = message.toLowerCase();

        // Retry on connection issues
        if (lowerMessage.contains("connection") ||
            lowerMessage.contains("timeout") ||
            lowerMessage.contains("temporarily unavailable") ||
            lowerMessage.contains("too many connections")) {
            return true;
        }

        // Retry on deadlocks
        if (lowerMessage.contains("deadlock") ||
            lowerMessage.contains("lock wait timeout")) {
            return true;
        }

        // Don't retry on validation, constraint violations, or business logic errors
        return false;
    }
}
