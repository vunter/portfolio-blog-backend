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
                // SEC-AH: Added Google reCAPTCHA v3 domains for analytics security
                String csp = ("default-src 'self'; " +
                        "script-src 'self' 'nonce-%s' https://www.google.com https://www.gstatic.com; " +
                        "style-src 'self' 'nonce-%s'; " +
                        "connect-src 'self' https://api.github.com https://www.google.com https://www.gstatic.com; " +
                        "img-src 'self' data: https://cdn.catananti.dev https://avatars.githubusercontent.com https://lh3.googleusercontent.com https://media.licdn.com; " +
                        "font-src 'self' https://fonts.gstatic.com https://cdnjs.cloudflare.com; " +
                        "frame-src https://www.google.com; " +
                        "object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; " +
                        "upgrade-insecure-requests; " +
                        "report-uri /api/v1/csp-report; report-to csp-endpoint;").formatted(nonce, nonce);
                exchange.getResponse().getHeaders().set("Content-Security-Policy", csp);
            } catch (UnsupportedOperationException e) {
                // Headers already read-only, skip
                log.trace("CSP header not set — headers already read-only: {}", e.getMessage(), e);
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
