package dev.catananti.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Centralised resilience settings: timeouts, retry strategies, and circuit
 * breakers. Inject this component directly into reactive pipelines:
 *
 * <pre>
 * return articleRepository.findBySlug(slug)
 *         .timeout(resilience.getDatabaseTimeout())
 *         .retryWhen(resilience.databaseRetry());
 * </pre>
 *
 * <p>Configuration is bound via {@link ResilienceProperties}; the
 * previous 14-parameter {@code @Value} constructor is replaced with a
 * single immutable record that groups settings by concern (database /
 * redis / external).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResilienceProperties.class)
@Getter
@Slf4j
public class ResilienceConfig {

    private final Duration databaseTimeout;
    private final Duration redisTimeout;
    private final Duration externalTimeout;
    private final int databaseRetryMaxAttempts;
    private final Duration databaseRetryMinBackoff;
    private final Duration databaseRetryMaxBackoff;
    private final CircuitBreaker databaseCircuitBreaker;
    private final CircuitBreaker oauthCircuitBreaker;
    private final CircuitBreaker cloudflareCircuitBreaker;
    private final CircuitBreaker emailCircuitBreaker;
    private final CircuitBreaker storageCircuitBreaker;

    public ResilienceConfig(ResilienceProperties props) {
        ResilienceProperties.Database db = props.database();
        ResilienceProperties.External ext = props.external();
        this.databaseTimeout = Duration.ofSeconds(db.timeoutSeconds());
        this.redisTimeout = Duration.ofSeconds(props.redis().timeoutSeconds());
        this.externalTimeout = Duration.ofSeconds(ext.timeoutSeconds());
        this.databaseRetryMaxAttempts = db.retryMaxAttempts();
        this.databaseRetryMinBackoff = Duration.ofMillis(db.retryMinBackoffMs());
        this.databaseRetryMaxBackoff = Duration.ofMillis(db.retryMaxBackoffMs());

        // Q9.2: Circuit breaker for database operations — fast-fails when DB is down
        CircuitBreakerConfig dbCbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(db.cbFailureRate())
                .waitDurationInOpenState(Duration.ofSeconds(db.cbWaitOpenSeconds()))
                .slidingWindowSize(db.cbSlidingWindowSize())
                .minimumNumberOfCalls(db.cbMinCalls())
                .build();
        this.databaseCircuitBreaker = CircuitBreaker.of("database", dbCbConfig);

        // Q3.4: Circuit breakers for external services
        CircuitBreakerConfig extCbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(ext.cbFailureRate())
                .waitDurationInOpenState(Duration.ofSeconds(ext.cbWaitOpenSeconds()))
                .slidingWindowSize(ext.cbSlidingWindowSize())
                .minimumNumberOfCalls(ext.cbMinCalls())
                .build();
        this.oauthCircuitBreaker = CircuitBreaker.of("oauth2", extCbConfig);
        this.cloudflareCircuitBreaker = CircuitBreaker.of("cloudflare", extCbConfig);
        this.emailCircuitBreaker = CircuitBreaker.of("email", extCbConfig);
        this.storageCircuitBreaker = CircuitBreaker.of("storage", extCbConfig);

        log.info("Resilience configuration initialized (DB circuit breaker: failureRate={}%, window={}, waitOpen={}s)",
                db.cbFailureRate(), db.cbSlidingWindowSize(), db.cbWaitOpenSeconds());
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

        if (lowerMessage.contains("connection") ||
            lowerMessage.contains("timeout") ||
            lowerMessage.contains("temporarily unavailable") ||
            lowerMessage.contains("too many connections")) {
            return true;
        }

        if (lowerMessage.contains("deadlock") ||
            lowerMessage.contains("lock wait timeout")) {
            return true;
        }

        return false;
    }
}
