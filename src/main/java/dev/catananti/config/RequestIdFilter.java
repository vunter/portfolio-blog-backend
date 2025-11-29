package dev.catananti.config;

import org.springframework.core.Ordered;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

/**
 * WebFilter that adds a unique request ID to each request for distributed tracing.
 * The request ID is:
 * 1. Read from X-Request-ID header if present (from upstream proxy)
 * 2. Generated if not present
 * 3. Added to response headers
 * 4. Added to Reactor Context for use in logging (MDC)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestIdFilter implements WebFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String REQUEST_ID_CONTEXT_KEY = "requestId";

    /** Max length for request/correlation IDs to prevent header injection. */
    private static final int MAX_ID_LENGTH = 64;
    /** Allowed characters: alphanumeric, hyphens, underscores. */
    private static final java.util.regex.Pattern VALID_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9\\-_]+$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Get or generate request ID — validate format if provided externally
        String externalRequestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        String requestId = sanitizeId(externalRequestId);
        if (requestId == null) {
            if (externalRequestId != null && !externalRequestId.isBlank()) {
                log.warn("Rejected external request ID");
            }
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        log.debug("Request ID filter applied: {}", requestId);
        
        // Get or use request ID as correlation ID
        String correlationId = sanitizeId(exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER));
        if (correlationId == null) {
            correlationId = requestId;
        }

        // Add to response headers
        final String finalRequestId = requestId;
        final String finalCorrelationId = correlationId;
        
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(REQUEST_ID_HEADER, finalRequestId)
                .header(CORRELATION_ID_HEADER, finalCorrelationId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        mutatedExchange.getResponse().getHeaders().add(REQUEST_ID_HEADER, finalRequestId);
        mutatedExchange.getResponse().getHeaders().add(CORRELATION_ID_HEADER, finalCorrelationId);

        // Add to Reactor Context for MDC propagation
        return chain.filter(mutatedExchange)
                .contextWrite(Context.of(
                        REQUEST_ID_CONTEXT_KEY, finalRequestId,
                        "correlationId", finalCorrelationId
                ));
    }

    /**
     * Sanitize an external ID header value.
     * Returns null if the value is blank, too long, or contains invalid characters.
     */
    private String sanitizeId(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > MAX_ID_LENGTH) return null;
        if (!VALID_ID_PATTERN.matcher(value).matches()) return null;
        return value;
    }
}
