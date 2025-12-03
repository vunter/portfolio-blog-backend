package dev.catananti.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Service for verifying Google reCAPTCHA v3 tokens.
 * <p>
 * reCAPTCHA v3 returns a score (0.0–1.0) indicating the likelihood
 * that the interaction is legitimate. Higher scores = more likely human.
 * </p>
 */
@Service
@Slf4j
public class RecaptchaService {

    private static final String VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";

    private final WebClient webClient;
    private final String secretKey;
    private final boolean enabled;
    private final double scoreThreshold;
    private final CircuitBreaker circuitBreaker;

    public RecaptchaService(
            WebClient.Builder webClientBuilder,
            @Value("${recaptcha.secret-key:}") String secretKey,
            @Value("${recaptcha.enabled:false}") boolean enabled,
            @Value("${recaptcha.score-threshold:0.5}") double scoreThreshold) {
        this.webClient = webClientBuilder.baseUrl(VERIFY_URL).build();
        this.secretKey = secretKey;
        this.enabled = enabled;
        this.scoreThreshold = scoreThreshold;

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .slidingWindowSize(5)
                .minimumNumberOfCalls(5)
                .build();
        this.circuitBreaker = CircuitBreaker.of("recaptcha-verify", cbConfig);
        log.info("reCAPTCHA circuit breaker initialised (failureRate=50%, window=5, waitOpen=60s)");
    }

    /**
     * Verify a reCAPTCHA token for a given action.
     *
     * @param token  the reCAPTCHA token from the frontend
     * @param action the expected action name (e.g., "contact", "login", "subscribe")
     * @return Mono that completes if valid, errors with IllegalArgumentException if invalid
     */
    public Mono<Void> verify(String token, String action) {
        if (!enabled) {
            log.debug("reCAPTCHA verification is disabled, skipping");
            return Mono.empty();
        }

        if (token == null || token.isBlank()) {
            log.warn("reCAPTCHA token is missing for action: {}", action);
            return Mono.error(new RecaptchaException("reCAPTCHA verification failed"));
        }

        return webClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // F-205: Use URLEncoder to prevent URL-encoding injection in token
                .bodyValue("secret=" + java.net.URLEncoder.encode(secretKey, java.nio.charset.StandardCharsets.UTF_8)
                        + "&response=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8))
                .retrieve()
                .bodyToMono(RecaptchaResponse.class)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .flatMap(response -> {
                    if (!response.success()) {
                        log.warn("reCAPTCHA verification failed for action '{}': errors={}", action, response.errorCodes());
                        return Mono.error(new RecaptchaException("reCAPTCHA verification failed"));
                    }

                    // Verify the action matches (prevents token reuse across forms)
                    if (action != null && response.action() != null && !action.equals(response.action())) {
                        log.warn("reCAPTCHA action mismatch: expected='{}', got='{}'", action, response.action());
                        return Mono.error(new RecaptchaException("reCAPTCHA verification failed"));
                    }

                    // Check score threshold
                    if (response.score() < scoreThreshold) {
                        log.warn("reCAPTCHA score too low for action '{}': score={}, threshold={}",
                                action, response.score(), scoreThreshold);
                        return Mono.error(new RecaptchaException("reCAPTCHA verification failed"));
                    }

                    log.debug("reCAPTCHA verified for action '{}': score={}", action, response.score());
                    return Mono.<Void>empty();
                })
                .onErrorResume(RecaptchaException.class, Mono::error)
                .onErrorResume(ex -> {
                    log.error("reCAPTCHA verification error for action '{}': {}", action, ex.getMessage());
                    // Fail-closed: reject the request if reCAPTCHA verification cannot be completed
                    return Mono.error(new RecaptchaException("reCAPTCHA verification unavailable. Please try again later."));
                })
                .then();
    }

    /**
     * Response from Google reCAPTCHA v3 siteverify API.
     */
    private record RecaptchaResponse(
            boolean success,
            double score,
            String action,
            String challenge_ts,
            String hostname,
            java.util.List<String> errorCodes
    ) {
        // Jackson will handle the mapping via @JsonProperty or field names
        @com.fasterxml.jackson.annotation.JsonCreator
        RecaptchaResponse(
                @com.fasterxml.jackson.annotation.JsonProperty("success") boolean success,
                @com.fasterxml.jackson.annotation.JsonProperty("score") double score,
                @com.fasterxml.jackson.annotation.JsonProperty("action") String action,
                @com.fasterxml.jackson.annotation.JsonProperty("challenge_ts") String challenge_ts,
                @com.fasterxml.jackson.annotation.JsonProperty("hostname") String hostname,
                @com.fasterxml.jackson.annotation.JsonProperty("error-codes") java.util.List<String> errorCodes
        ) {
            this.success = success;
            this.score = score;
            this.action = action;
            this.challenge_ts = challenge_ts;
            this.hostname = hostname;
            this.errorCodes = errorCodes;
        }
    }

    /**
     * Exception thrown when reCAPTCHA verification fails.
     */
    public static class RecaptchaException extends RuntimeException {
        public RecaptchaException(String message) {
            super(message);
        }
    }
}
