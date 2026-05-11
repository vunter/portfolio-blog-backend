package dev.catananti.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Q3.5: Global safety-net timeout on all API reactive chains.
 * Prevents any request from blocking indefinitely if a downstream operation hangs.
 * Individual timeouts on DB/HTTP calls should fire first; this is the last resort.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@Slf4j
public class GlobalTimeoutFilter implements WebFilter {

    private final Duration timeout;

    public GlobalTimeoutFilter(
            @Value("${resilience.global.timeout-seconds:30}") int timeoutSeconds) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Skip timeout for streaming/upload endpoints
        if (path.startsWith("/api/v1/admin/media") ||
            path.startsWith("/api/v1/admin/export") ||
            path.startsWith("/api/v1/resume/pdf")) {
            return chain.filter(exchange);
        }

        return chain.filter(exchange)
                .timeout(timeout)
                .onErrorResume(TimeoutException.class, ex -> {
                    log.error("Global timeout ({}s) exceeded for {} {}",
                            timeout.getSeconds(),
                            exchange.getRequest().getMethod(),
                            path);
                    exchange.getResponse().setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
                    return exchange.getResponse().setComplete();
                });
    }
}
