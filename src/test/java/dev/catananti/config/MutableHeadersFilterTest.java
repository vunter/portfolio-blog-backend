package dev.catananti.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("MutableHeadersFilter Tests")
class MutableHeadersFilterTest {

    private MutableHeadersFilter filter;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new MutableHeadersFilter();
        chain = mock(WebFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Should decorate response with mutable headers")
    void shouldDecorateResponseWithMutableHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Chain should be called with a mutated exchange
        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("Should pass mutated exchange to chain with decorated response")
    void shouldPassMutatedExchangeToChain() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Capture the exchange passed to chain
        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange mutatedExchange = invocation.getArgument(0);
            // The response should have accessible headers
            HttpHeaders headers = mutatedExchange.getResponse().getHeaders();
            assertThat(headers).isNotNull();
            // Should be able to set headers on the decorated response
            headers.set("X-Test-Header", "test-value");
            assertThat(headers.getFirst("X-Test-Header")).isEqualTo("test-value");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("Should allow setting Cache-Control on decorated response")
    void shouldAllowSettingCacheControl() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange mutated = invocation.getArgument(0);
            HttpHeaders headers = mutated.getResponse().getHeaders();
            headers.setCacheControl("no-store");
            assertThat(headers.getCacheControl()).isEqualTo("no-store");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should allow setting Content-Type on decorated response")
    void shouldAllowSettingContentType() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange mutated = invocation.getArgument(0);
            HttpHeaders headers = mutated.getResponse().getHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
            assertThat(headers.getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/json");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should allow setting multiple security headers on decorated response")
    void shouldAllowSettingMultipleHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange mutated = invocation.getArgument(0);
            HttpHeaders headers = mutated.getResponse().getHeaders();
            headers.set("X-Frame-Options", "DENY");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Strict-Transport-Security", "max-age=31536000");
            assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
            assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(headers.getFirst("Strict-Transport-Security")).isEqualTo("max-age=31536000");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should preserve original request through filter")
    void shouldPreserveOriginalRequest() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("Authorization", "Bearer token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange mutated = invocation.getArgument(0);
            // Original request should be preserved
            assertThat(mutated.getRequest().getPath().value()).isEqualTo("/api/test");
            assertThat(mutated.getRequest().getHeaders().getFirst("Authorization")).isEqualTo("Bearer token");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle POST request without issues")
    void shouldHandlePostRequest() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/articles")
                .body("{}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should allow setting ETag header on decorated response")
    void shouldAllowSettingEtag() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange mutated = invocation.getArgument(0);
            HttpHeaders headers = mutated.getResponse().getHeaders();
            headers.setETag("\"abc123\"");
            assertThat(headers.getETag()).isEqualTo("\"abc123\"");
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should allow setting Pragma and Expires on decorated response")
    void shouldAllowSettingPragmaAndExpires() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(chain.filter(any(ServerWebExchange.class))).thenAnswer(invocation -> {
            ServerWebExchange mutated = invocation.getArgument(0);
            HttpHeaders headers = mutated.getResponse().getHeaders();
            headers.setPragma("no-cache");
            headers.setExpires(0);
            assertThat(headers.getPragma()).isEqualTo("no-cache");
            assertThat(headers.getExpires()).isEqualTo(0);
            return Mono.empty();
        });

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }
}
