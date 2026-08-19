package dev.catananti.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyKeyFilter")
class IdempotencyKeyFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    private IdempotencyKeyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new IdempotencyKeyFilter(redisTemplate, 24, 60);
    }

    @Nested
    @DisplayName("filter")
    class Filter {

        @Test
        @DisplayName("should pass through GET requests without checking idempotency")
        void shouldPassThroughGetRequests() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/articles").build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("should pass through POST without idempotency key header")
        void shouldPassThroughWithoutHeader() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/articles").build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }

        @Test
        @DisplayName("should reject invalid UUID format")
        void shouldRejectInvalidUuid() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/articles")
                            .header("X-Idempotency-Key", "not-a-uuid")
                            .build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should process first request with valid UUID key")
        void shouldProcessFirstRequest() {
            String key = UUID.randomUUID().toString();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq("idem:a:unknown:" + key), eq("processing"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(valueOps.set(eq("idem:a:unknown:" + key), anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/articles")
                            .header("X-Idempotency-Key", key)
                            .build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should reject duplicate request with 409 Conflict")
        void shouldRejectDuplicateRequest() {
            String key = UUID.randomUUID().toString();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq("idem:a:unknown:" + key), eq("processing"), any(Duration.class)))
                    .thenReturn(Mono.just(false));

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/articles")
                            .header("X-Idempotency-Key", key)
                            .build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("should remove key on request failure to allow retry")
        void shouldRemoveKeyOnFailure() {
            String key = UUID.randomUUID().toString();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq("idem:a:unknown:" + key), eq("processing"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.delete(eq("idem:a:unknown:" + key)))
                    .thenReturn(Mono.just(1L));

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/articles")
                            .header("X-Idempotency-Key", key)
                            .build());
            RuntimeException error = new RuntimeException("Processing failed");
            WebFilterChain chain = e -> Mono.error(error);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectErrorMatches(e -> e.getMessage().equals("Processing failed"))
                    .verify();

            verify(redisTemplate).delete("idem:a:unknown:" + key);
        }

        @Test
        @DisplayName("AUD18-M4: should store done:<status> with full TTL on success")
        void shouldStoreDoneKeyOnSuccess() {
            String key = UUID.randomUUID().toString();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq("idem:a:unknown:" + key), eq("processing"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(valueOps.set(eq("idem:a:unknown:" + key), eq("done:200"), eq(Duration.ofHours(24))))
                    .thenReturn(Mono.just(true));

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/articles")
                            .header("X-Idempotency-Key", key)
                            .build());
            WebFilterChain chain = e -> {
                e.getResponse().setStatusCode(HttpStatus.OK);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(valueOps).set(eq("idem:a:unknown:" + key), eq("done:200"), eq(Duration.ofHours(24)));
            verify(redisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("AUD18-M4: should release key when the response is an error status "
                + "(GlobalExceptionHandler converts exceptions to normal responses)")
        void shouldReleaseKeyOnErrorStatusResponse() {
            String key = UUID.randomUUID().toString();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq("idem:a:unknown:" + key), eq("processing"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.delete(eq("idem:a:unknown:" + key)))
                    .thenReturn(Mono.just(1L));

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/articles")
                            .header("X-Idempotency-Key", key)
                            .build());
            // Chain completes NORMALLY with a 500 response — this is what the exchange
            // looks like after GlobalExceptionHandler turns an exception into a response.
            WebFilterChain chain = e -> {
                e.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(redisTemplate).delete("idem:a:unknown:" + key);
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("AUD18-M4: failed request then retry with the same key succeeds")
        void failedRequestThenRetrySucceeds() {
            String key = UUID.randomUUID().toString();
            String redisKey = "idem:a:unknown:" + key;
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            // Both attempts win the SETNX (the key was deleted after the failure)
            when(valueOps.setIfAbsent(eq(redisKey), eq("processing"), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(redisTemplate.delete(eq(redisKey))).thenReturn(Mono.just(1L));
            when(valueOps.set(eq(redisKey), eq("done:201"), eq(Duration.ofHours(24))))
                    .thenReturn(Mono.just(true));

            // First attempt: handler answers 400
            MockServerWebExchange failedExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/articles")
                            .header("X-Idempotency-Key", key)
                            .build());
            StepVerifier.create(filter.filter(failedExchange, e -> {
                        e.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                        return Mono.empty();
                    }))
                    .verifyComplete();
            verify(redisTemplate).delete(redisKey);

            // Retry: same key, handler now succeeds with 201 — processed, not 409'd
            MockServerWebExchange retryExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/articles")
                            .header("X-Idempotency-Key", key)
                            .build());
            StepVerifier.create(filter.filter(retryExchange, e -> {
                        e.getResponse().setStatusCode(HttpStatus.CREATED);
                        return Mono.empty();
                    }))
                    .verifyComplete();

            assertThat(retryExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(valueOps).set(eq(redisKey), eq("done:201"), eq(Duration.ofHours(24)));
        }

        @Test
        @DisplayName("should work with PUT requests")
        void shouldWorkWithPutRequests() {
            String key = UUID.randomUUID().toString();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(valueOps.set(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.put("/api/v1/articles/1")
                            .header("X-Idempotency-Key", key)
                            .build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should work with PATCH requests")
        void shouldWorkWithPatchRequests() {
            String key = UUID.randomUUID().toString();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(true));
            when(valueOps.set(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.patch("/api/v1/articles/1")
                            .header("X-Idempotency-Key", key)
                            .build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should pass through DELETE requests")
        void shouldPassThroughDeleteRequests() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.delete("/api/v1/articles/1")
                            .header("X-Idempotency-Key", UUID.randomUUID().toString())
                            .build());
            WebFilterChain chain = e -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verifyNoInteractions(redisTemplate);
        }
    }
}
