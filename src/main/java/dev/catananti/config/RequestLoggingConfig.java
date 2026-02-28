package dev.catananti.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebFilter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Configuration for request/response logging.
 * F-032: Sensitive paths are excluded from DEBUG body logging to prevent PII leakage.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class RequestLoggingConfig {

    /** Paths whose request/response bodies must never be logged, even at DEBUG level. */
    private static final List<String> PII_SENSITIVE_PATHS = List.of(
            "/auth/login", "/auth/register", "/auth/refresh",
            "/reset-password", "/forgot-password",
            "/api/v1/users/me", "/api/v1/contact"
    );

    @Bean
    public WebFilter correlationIdFilter() {
        return (exchange, chain) -> {
            String correlationId = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = java.util.UUID.randomUUID().toString();
            }
            exchange.getResponse().getHeaders().set("X-Correlation-Id", correlationId);
            final String cid = correlationId;
            return chain.filter(exchange)
                    .contextWrite(ctx -> ctx.put("correlationId", cid));
        };
    }

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
                        // SSE/stream client disconnects are expected, not errors
                        if (error.getMessage() != null && error.getMessage().contains("Connection has been closed")) {
                            log.debug("[{}] {} {} from {} - client disconnected in {}ms",
                                    requestId, method, path, clientIp, duration.toMillis());
                        } else {
                            log.error("[{}] {} {} from {} - ERROR {} in {}ms",
                                    requestId, method, path, clientIp, error.getMessage(), duration.toMillis());
                        }
                    });
        };
    }

    /** F-032: Check whether the path handles PII and must not have its body logged. */
    private boolean isSensitivePath(String path) {
        return PII_SENSITIVE_PATHS.stream().anyMatch(path::contains);
    }

    private void logRequest(String requestId, String method, String path, String clientIp, int status, Duration duration) {
        if (path.contains("/actuator") || path.contains("/swagger") || path.contains("/v3/api-docs")) {
            log.trace("[{}] {} {} from {} - {} in {}ms", requestId, method, path, clientIp, status, duration.toMillis());
        } else if (status >= 500) {
            log.error("[{}] {} {} from {} - {} in {}ms", requestId, method, path, clientIp, status, duration.toMillis());
        } else if (status >= 400) {
            // 4xx are client errors — expected behavior, not server issues
            log.debug("[{}] {} {} from {} - {} in {}ms", requestId, method, path, clientIp, status, duration.toMillis());
        } else {
            if (isSensitivePath(path)) {
                log.info("[{}] {} {} from {} - {} in {}ms [body-redacted]", requestId, method, path, clientIp, status, duration.toMillis());
            } else {
                log.info("[{}] {} {} from {} - {} in {}ms", requestId, method, path, clientIp, status, duration.toMillis());
            }
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
