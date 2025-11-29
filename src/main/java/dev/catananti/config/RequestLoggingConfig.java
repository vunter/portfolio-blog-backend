package dev.catananti.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebFilter;

import java.time.Duration;
import java.time.Instant;

/**
 * Configuration for request/response logging.
 * TODO F-032: Ensure DEBUG-level body logging does not capture PII (passwords, tokens, personal data)
 * TODO F-033: Propagate correlation ID (e.g., X-Correlation-Id) across service boundaries for distributed tracing
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class RequestLoggingConfig {

    @Bean
    public WebFilter requestLoggingFilter() {
        return (exchange, chain) -> {
            Instant start = Instant.now();
            String method = exchange.getRequest().getMethod().name();
            String path = exchange.getRequest().getPath().value();
            String clientIp = getClientIp(exchange.getRequest());

            // Read request ID from RequestIdFilter (avoid generating duplicate)
            String rawRequestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
            final String requestId = (rawRequestId == null || rawRequestId.isBlank())
                    ? java.util.UUID.randomUUID().toString().substring(0, 8)
                    : rawRequestId;

            return chain.filter(exchange)
                    .doOnSuccess(aVoid -> {
                        Duration duration = Duration.between(start, Instant.now());
                        HttpStatus status = exchange.getResponse().getStatusCode() != null 
                                ? HttpStatus.valueOf(exchange.getResponse().getStatusCode().value()) 
                                : HttpStatus.OK;
                        
                        logRequest(requestId, method, path, clientIp, status.value(), duration);
                    })
                    .doOnError(error -> {
                        Duration duration = Duration.between(start, Instant.now());
                        log.error("[{}] {} {} from {} - ERROR {} in {}ms", 
                                requestId, method, path, clientIp, error.getMessage(), duration.toMillis());
                    });
        };
    }

    private void logRequest(String requestId, String method, String path, String clientIp, int status, Duration duration) {
        if (path.contains("/actuator") || path.contains("/swagger") || path.contains("/v3/api-docs")) {
            log.trace("[{}] {} {} from {} - {} in {}ms", requestId, method, path, clientIp, status, duration.toMillis());
        } else if (status >= 400) {
            log.warn("[{}] {} {} from {} - {} in {}ms", requestId, method, path, clientIp, status, duration.toMillis());
        } else {
            log.info("[{}] {} {} from {} - {} in {}ms", requestId, method, path, clientIp, status, duration.toMillis());
        }
    }

    // F-031: Sanitize header values to prevent log injection via X-Forwarded-For
    private String getClientIp(org.springframework.http.server.reactive.ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            return sanitizeHeaderValue(forwardedFor.split(",")[0].trim());
        }
        
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return sanitizeHeaderValue(realIp);
        }
        
        if (request.getRemoteAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        
        return "unknown";
    }

    /** F-031: Strip newlines and non-printable chars to prevent log injection */
    private String sanitizeHeaderValue(String value) {
        return value.replaceAll("[\\r\\n]", "").replaceAll("[^\\x20-\\x7E]", "");
    }
}
