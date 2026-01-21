package dev.catananti.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CspNonceFilter")
class CspNonceFilterTest {

    private CspNonceFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CspNonceFilter();
    }

    private MockServerWebExchange createExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
    }

    /**
     * A chain implementation that captures the (possibly mutated) exchange
     * passed to chain.filter(), so we can inspect the decorated response.
     */
    private static class CapturingChain implements WebFilterChain {
        private final AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            captured.set(exchange);
            return Mono.empty();
        }

        ServerWebExchange getCapturedExchange() {
            return captured.get();
        }
    }

    @Nested
    @DisplayName("Nonce generation")
    class NonceGeneration {

        @Test
        @DisplayName("should store nonce as exchange attribute")
        void shouldStoreNonceAsExchangeAttribute() {
            MockServerWebExchange exchange = createExchange();
            CapturingChain chain = new CapturingChain();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            Object nonce = exchange.getAttribute(CspNonceFilter.CSP_NONCE_ATTRIBUTE);
            assertThat(nonce).isNotNull();
            assertThat(nonce).isInstanceOf(String.class);
        }

        @Test
        @DisplayName("nonce should be non-null and non-empty")
        void nonceShouldBeNonNullAndNonEmpty() {
            MockServerWebExchange exchange = createExchange();
            CapturingChain chain = new CapturingChain();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            String nonce = exchange.getAttribute(CspNonceFilter.CSP_NONCE_ATTRIBUTE);
            assertThat(nonce).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("nonce should be different for each request")
        void nonceShouldBeDifferentForEachRequest() {
            MockServerWebExchange exchange1 = createExchange();
            MockServerWebExchange exchange2 = createExchange();
            CapturingChain chain1 = new CapturingChain();
            CapturingChain chain2 = new CapturingChain();

            StepVerifier.create(filter.filter(exchange1, chain1))
                    .verifyComplete();
            StepVerifier.create(filter.filter(exchange2, chain2))
                    .verifyComplete();

            String nonce1 = exchange1.getAttribute(CspNonceFilter.CSP_NONCE_ATTRIBUTE);
            String nonce2 = exchange2.getAttribute(CspNonceFilter.CSP_NONCE_ATTRIBUTE);

            assertThat(nonce1).isNotEqualTo(nonce2);
        }

        @Test
        @DisplayName("nonce should be Base64 URL encoded (22 chars for 16 bytes without padding)")
        void nonceShouldBeBase64UrlEncoded() {
            MockServerWebExchange exchange = createExchange();
            CapturingChain chain = new CapturingChain();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            String nonce = exchange.getAttribute(CspNonceFilter.CSP_NONCE_ATTRIBUTE);
            assertThat(nonce).hasSize(22); // 16 bytes → ceil(16*4/3) = 22 chars without padding

            // Should be valid Base64 URL encoding (no exception thrown)
            byte[] decoded = Base64.getUrlDecoder().decode(nonce);
            assertThat(decoded).hasSize(16);
        }
    }

    @Nested
    @DisplayName("CSP header")
    class CspHeader {

        @Test
        @DisplayName("should contain the nonce in script-src and style-src")
        void cspHeaderShouldContainNonce() {
            MockServerWebExchange exchange = createExchange();
            CapturingChain chain = new CapturingChain();

            // Filter registers beforeCommit — trigger it via setComplete()
            StepVerifier.create(filter.filter(exchange, chain)
                            .then(exchange.getResponse().setComplete()))
                    .verifyComplete();

            String nonce = exchange.getAttribute(CspNonceFilter.CSP_NONCE_ATTRIBUTE);
            HttpHeaders headers = exchange.getResponse().getHeaders();
            String csp = headers.getFirst("Content-Security-Policy");

            assertThat(csp).isNotNull();
            assertThat(csp).contains("'nonce-" + nonce + "'");
            assertThat(csp).contains("script-src 'self' 'nonce-" + nonce + "'");
            assertThat(csp).contains("style-src 'self' 'nonce-" + nonce + "'");
        }

        @Test
        @DisplayName("should have self directives")
        void cspHeaderShouldHaveSelfDirectives() {
            MockServerWebExchange exchange = createExchange();
            CapturingChain chain = new CapturingChain();

            StepVerifier.create(filter.filter(exchange, chain)
                            .then(exchange.getResponse().setComplete()))
                    .verifyComplete();

            String csp = exchange.getResponse().getHeaders().getFirst("Content-Security-Policy");

            assertThat(csp).contains("default-src 'self'");
            assertThat(csp).contains("base-uri 'self'");
            assertThat(csp).contains("form-action 'self'");
            assertThat(csp).contains("font-src 'self' https:");
        }

        @Test
        @DisplayName("should have frame-ancestors none")
        void cspHeaderShouldHaveFrameAncestorsNone() {
            MockServerWebExchange exchange = createExchange();
            CapturingChain chain = new CapturingChain();

            StepVerifier.create(filter.filter(exchange, chain)
                            .then(exchange.getResponse().setComplete()))
                    .verifyComplete();

            String csp = exchange.getResponse().getHeaders().getFirst("Content-Security-Policy");

            assertThat(csp).contains("frame-ancestors 'none'");
        }

        @Test
        @DisplayName("should have img-src with data: and https:")
        void cspHeaderShouldHaveImgSrcWithDataAndHttps() {
            MockServerWebExchange exchange = createExchange();
            CapturingChain chain = new CapturingChain();

            StepVerifier.create(filter.filter(exchange, chain)
                            .then(exchange.getResponse().setComplete()))
                    .verifyComplete();

            String csp = exchange.getResponse().getHeaders().getFirst("Content-Security-Policy");

            assertThat(csp).contains("img-src 'self' data: https:");
        }
    }

    @Nested
    @DisplayName("Filter chain")
    class FilterChain {

        @Test
        @DisplayName("should call next filter in chain")
        void shouldCallNextFilterInChain() {
            MockServerWebExchange exchange = createExchange();
            CapturingChain chain = new CapturingChain();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(chain.getCapturedExchange()).isNotNull();
        }

        @Test
        @DisplayName("should pass exchange unchanged to chain (CSP via beforeCommit)")
        void shouldPassMutatedExchangeToChain() {
            MockServerWebExchange exchange = createExchange();
            CapturingChain chain = new CapturingChain();

            StepVerifier.create(filter.filter(exchange, chain)
                            .then(exchange.getResponse().setComplete()))
                    .verifyComplete();

            // Filter passes the same exchange (CSP set via beforeCommit callback, not mutate())
            assertThat(chain.getCapturedExchange()).isSameAs(exchange);
            // CSP header present after response committed
            assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy")).isNotNull();
        }
    }
}
