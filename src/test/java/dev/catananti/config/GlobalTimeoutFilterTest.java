package dev.catananti.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalTimeoutFilter")
class GlobalTimeoutFilterTest {

    private GlobalTimeoutFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GlobalTimeoutFilter(30);
    }

    @Nested
    @DisplayName("filter")
    class Filter {

        @Test
        @DisplayName("should pass through requests that complete within timeout")
        void shouldPassThroughNormalRequests() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/articles").build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return 504 when request exceeds timeout")
        void shouldReturn504OnTimeout() {
            GlobalTimeoutFilter shortFilter = new GlobalTimeoutFilter(1);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/articles").build());
            WebFilterChain chain = e -> Mono.never();

            StepVerifier.create(shortFilter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        }

        @Test
        @DisplayName("should skip timeout for media endpoints")
        void shouldSkipMediaEndpoints() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/admin/media/upload").build());
            WebFilterChain chain = e -> Mono.delay(Duration.ofMillis(50)).then();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should skip timeout for export endpoints")
        void shouldSkipExportEndpoints() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/admin/export/data").build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should skip timeout for PDF endpoints")
        void shouldSkipPdfEndpoints() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/resume/pdf").build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }
    }
}
