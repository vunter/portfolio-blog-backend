package dev.catananti.config;

import org.springframework.core.Ordered;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Web filter that generates a CSP nonce per request and replaces
 * Spring Security's static CSP header with a nonce-aware version.
 * The nonce is stored as a request attribute for use by SSR templates.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
@Slf4j
public class CspNonceFilter implements WebFilter {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 16;

    public static final String CSP_NONCE_ATTRIBUTE = "cspNonce";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        log.debug("Generating CSP nonce for request: {}", exchange.getRequest().getPath());
        String nonce = generateNonce();
        exchange.getAttributes().put(CSP_NONCE_ATTRIBUTE, nonce);

        // Set CSP header before the response is committed
        exchange.getResponse().beforeCommit(() -> {
            try {
                // F-026: Added connect-src 'self' to restrict fetch/XHR destinations
                String csp = ("default-src 'self'; script-src 'self' 'nonce-%s'; style-src 'self' 'nonce-%s'; " +
                        "connect-src 'self' https://api.github.com https://www.google.com; img-src 'self' data: https:; font-src 'self' https:; " +
                        "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; " +
                        "report-uri /api/csp-report; report-to csp-endpoint;").formatted(nonce, nonce);
                exchange.getResponse().getHeaders().set("Content-Security-Policy", csp);
            } catch (UnsupportedOperationException e) {
                // Headers already read-only, skip
                log.debug("CSP header not set — headers already read-only: {}", e.getMessage());
            }
            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    private String generateNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
