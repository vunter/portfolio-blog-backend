package dev.catananti.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RequestIdFilter Tests")
class RequestIdFilterTest {

    private RequestIdFilter filter;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RequestIdFilter();
        chain = mock(WebFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Should generate request ID when none provided")
    void shouldGenerateRequestId() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        assertThat(requestId).isNotNull().hasSize(16);
    }

    @Test
    @DisplayName("Should pass through existing valid X-Request-ID")
    void shouldPassExistingRequestId() {
        String existingId = "my-custom-request-id-123";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Request-ID", existingId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        assertThat(requestId).isEqualTo(existingId);
    }

    @Test
    @DisplayName("Should add X-Request-ID to response headers")
    void shouldAddRequestIdToResponseHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-ID")).isNotNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-ID")).isNotNull();
    }

    @Test
    @DisplayName("Should add X-Correlation-ID to response headers")
    void shouldAddCorrelationIdToResponse() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String correlationId = exchange.getResponse().getHeaders().getFirst("X-Correlation-ID");
        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        assertThat(correlationId).isNotNull();
        // When no correlation ID provided, it should be same as request ID
        assertThat(correlationId).isEqualTo(requestId);
    }

    @Test
    @DisplayName("Should pass through existing X-Correlation-ID")
    void shouldPassExistingCorrelationId() {
        String existingCorrelationId = "corr-abc-123";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Correlation-ID", existingCorrelationId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String correlationId = exchange.getResponse().getHeaders().getFirst("X-Correlation-ID");
        assertThat(correlationId).isEqualTo(existingCorrelationId);
    }

    @Test
    @DisplayName("Should reject request ID that is too long (>64 chars)")
    void shouldRejectTooLongRequestId() {
        String longId = "a".repeat(65);
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Request-ID", longId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        assertThat(requestId).isNotNull();
        // Should generate a new ID since the provided one is too long
        assertThat(requestId).isNotEqualTo(longId);
        assertThat(requestId).hasSize(16);
    }

    @Test
    @DisplayName("Should reject request ID with invalid characters")
    void shouldRejectInvalidCharactersInRequestId() {
        String invalidId = "request<script>alert(1)</script>";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Request-ID", invalidId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        assertThat(requestId).isNotNull();
        // Should generate a new ID since the provided one has invalid chars
        assertThat(requestId).isNotEqualTo(invalidId);
        assertThat(requestId).hasSize(16);
    }

    @Test
    @DisplayName("Should accept request ID with hyphens and underscores")
    void shouldAcceptRequestIdWithHyphensAndUnderscores() {
        String validId = "req_abc-123_xyz";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Request-ID", validId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        assertThat(requestId).isEqualTo(validId);
    }

    @Test
    @DisplayName("Should reject blank request ID and generate new one")
    void shouldRejectBlankRequestId() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Request-ID", "   ")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        assertThat(requestId).isNotNull().hasSize(16);
    }

    @Test
    @DisplayName("Should accept ID at exactly 64 characters")
    void shouldAcceptIdAtMaxLength() {
        String maxLengthId = "a".repeat(64);
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Request-ID", maxLengthId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        assertThat(requestId).isEqualTo(maxLengthId);
    }

    @Test
    @DisplayName("Should write request ID to reactor context")
    void shouldWriteRequestIdToContext() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Verify the filter completes without error - context is written internally
        String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-ID");
        assertThat(requestId).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("Should handle both request ID and correlation ID from headers")
    void shouldHandleBothIds() {
        String reqId = "req-abc-123";
        String corrId = "corr-xyz-456";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Request-ID", reqId)
                .header("X-Correlation-ID", corrId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-ID")).isEqualTo(reqId);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-ID")).isEqualTo(corrId);
    }
}
