package dev.catananti.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Workaround for Spring Framework 7.0.3 regression where
 * {@code AbstractServerHttpResponse.getHeaders()} caches a
 * {@code ReadOnlyHttpHeaders} instance once the response enters the
 * COMMITTED state.  The cached wrapper is then returned on every
 * subsequent call — even from
 * {@code EncoderHttpMessageWriter.lambda$write$1} which still needs
 * to set Content-Length before the body is written.
 * <p>
 * This filter decorates the response so that {@link #getHeaders()}
 * always delegates to the real (mutable) headers obtained while the
 * response is still in the NEW state, thereby preventing the
 * {@link java.lang.UnsupportedOperationException} thrown by
 * {@code ReadOnlyHttpHeaders.set()}.
 * <p>
 * <b>Remove this filter after upgrading to a stable Spring Boot 4.x
 * or Spring Framework 7.x release that fixes the behaviour.</b>
 *
 * @see <a href="https://github.com/spring-projects/spring-framework/blob/v7.0.3/spring-web/src/main/java/org/springframework/http/server/reactive/AbstractServerHttpResponse.java">
 *     AbstractServerHttpResponse (7.0.3)</a>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE - 1)   // runs before every other filter
public class MutableHeadersFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse original = exchange.getResponse();

        // Capture the mutable headers while state is NEW
        HttpHeaders mutableHeaders = original.getHeaders();

        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(original) {
            @Override
            public HttpHeaders getHeaders() {
                // Always return the mutable instance, bypassing the
                // readOnlyHeaders cache introduced in 7.0.3.
                return mutableHeaders;
            }
        };

        return chain.filter(
                exchange.mutate().response(decorated).build()
        );
    }
}
