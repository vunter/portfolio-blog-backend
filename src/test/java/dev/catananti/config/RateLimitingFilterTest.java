package dev.catananti.config;

import dev.catananti.security.JwtTokenProvider;
import dev.catananti.util.IpAddressExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.Principal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitingFilter")
class RateLimitingFilterTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveZSetOperations<String, String> zSetOperations;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private WebFilterChain chain;

    private RateLimitingFilter filter;

    private static final int MAX_AUTHENTICATED = 100;
    private static final int MAX_ANONYMOUS = 30;
    private static final int MAX_LOGIN = 10;
    private static final int WINDOW_SECONDS = 60;
    private static final String TEST_IP = "192.168.1.100";

    @BeforeEach
    void setUp() {
        filter = new RateLimitingFilter(
                redisTemplate,
                tokenProvider,
                MAX_AUTHENTICATED,
                MAX_ANONYMOUS,
                MAX_LOGIN,
                WINDOW_SECONDS
        );
    }

    private MockServerWebExchange buildExchange(String path) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path)
                .remoteAddress(new java.net.InetSocketAddress(TEST_IP, 0))
                .build();
        return MockServerWebExchange.from(request);
    }

    @SuppressWarnings("unchecked")
    private void stubRedisIncrement(String key, long returnCount) {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.removeRangeByScore(eq(key), any(Range.class))).thenReturn(Mono.just(0L));
        when(zSetOperations.add(eq(key), anyString(), anyDouble())).thenReturn(Mono.just(true));
        when(zSetOperations.size(eq(key))).thenReturn(Mono.just(returnCount));
        when(redisTemplate.expire(eq(key), any(Duration.class))).thenReturn(Mono.just(true));
    }

    @SuppressWarnings("unchecked")
    private void stubRedisIncrementForAnyKey(long returnCount) {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.removeRangeByScore(anyString(), any(Range.class))).thenReturn(Mono.just(0L));
        when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
        when(zSetOperations.size(anyString())).thenReturn(Mono.just(returnCount));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    }

    // ──────────────────────────────────────────────────────────────
    // Health / Liveness / Readiness bypass
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Health endpoint bypass")
    class HealthEndpointBypass {

        @Test
        @DisplayName("Should skip rate limiting for /actuator/health")
        void shouldSkipForActuatorHealth() {
            MockServerWebExchange exchange = buildExchange("/actuator/health");
            when(chain.filter(exchange)).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // No rate limit headers should be set
            assertThat(exchange.getResponse().getHeaders().get("X-RateLimit-Limit")).isNull();
        }

        @Test
        @DisplayName("Should skip rate limiting for /actuator/health/readiness sub-path")
        void shouldSkipForActuatorHealthSubpath() {
            MockServerWebExchange exchange = buildExchange("/actuator/health/readiness");
            when(chain.filter(exchange)).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().get("X-RateLimit-Limit")).isNull();
        }

        @Test
        @DisplayName("Should skip rate limiting for /livez")
        void shouldSkipForLivez() {
            MockServerWebExchange exchange = buildExchange("/livez");
            when(chain.filter(exchange)).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().get("X-RateLimit-Limit")).isNull();
        }

        @Test
        @DisplayName("Should skip rate limiting for /readyz")
        void shouldSkipForReadyz() {
            MockServerWebExchange exchange = buildExchange("/readyz");
            when(chain.filter(exchange)).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().get("X-RateLimit-Limit")).isNull();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Normal request within rate limit
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Requests within rate limit")
    class WithinRateLimit {

        @Test
        @DisplayName("Should add rate limit headers on normal anonymous request")
        void shouldAddRateLimitHeaders() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                String expectedKey = "rate_limit:" + TEST_IP;
                MockServerWebExchange exchange = buildExchange("/api/posts");

                stubRedisIncrement(expectedKey, 1L);
                when(chain.filter(exchange)).thenReturn(Mono.empty());

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"))
                        .isEqualTo(String.valueOf(MAX_ANONYMOUS));
                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                        .isEqualTo(String.valueOf(MAX_ANONYMOUS - 1));
                assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            }
        }

        @Test
        @DisplayName("Should show correct remaining count on subsequent requests")
        void shouldDecrementRemaining() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                String expectedKey = "rate_limit:" + TEST_IP;
                MockServerWebExchange exchange = buildExchange("/api/posts");

                stubRedisIncrement(expectedKey, 5L);
                when(chain.filter(exchange)).thenReturn(Mono.empty());

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                        .isEqualTo(String.valueOf(MAX_ANONYMOUS - 5));
            }
        }

        @Test
        @DisplayName("Should use authenticated limit when principal present")
        void shouldUseAuthenticatedLimit() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                String expectedKey = "rate_limit:" + TEST_IP;
                MockServerHttpRequest request = MockServerHttpRequest.get("/api/posts")
                        .remoteAddress(new java.net.InetSocketAddress(TEST_IP, 0))
                        .build();
                MockServerWebExchange exchange = MockServerWebExchange.builder(request)
                        .principal((Principal) () -> "testuser")
                        .build();

                stubRedisIncrement(expectedKey, 1L);
                when(chain.filter(exchange)).thenReturn(Mono.empty());

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"))
                        .isEqualTo(String.valueOf(MAX_AUTHENTICATED));
                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                        .isEqualTo(String.valueOf(MAX_AUTHENTICATED - 1));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Rate limit exceeded (429)
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Rate limit exceeded")
    class RateLimitExceeded {

        @Test
        @DisplayName("Should return 429 when rate limit exceeded for anonymous request")
        void shouldReturn429WhenExceeded() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                String expectedKey = "rate_limit:" + TEST_IP;
                MockServerWebExchange exchange = buildExchange("/api/posts");

                stubRedisIncrement(expectedKey, (long) MAX_ANONYMOUS + 1);

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            }
        }

        @Test
        @DisplayName("Should add Retry-After header when limited")
        void shouldAddRetryAfterHeader() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                String expectedKey = "rate_limit:" + TEST_IP;
                MockServerWebExchange exchange = buildExchange("/api/posts");

                stubRedisIncrement(expectedKey, (long) MAX_ANONYMOUS + 1);

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After"))
                        .isEqualTo(String.valueOf(WINDOW_SECONDS));
            }
        }

        @Test
        @DisplayName("Should add X-RateLimit-Reset header when limited")
        void shouldAddRateLimitResetHeader() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                String expectedKey = "rate_limit:" + TEST_IP;
                MockServerWebExchange exchange = buildExchange("/api/posts");

                stubRedisIncrement(expectedKey, (long) MAX_ANONYMOUS + 1);

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                String resetHeader = exchange.getResponse().getHeaders().getFirst("X-RateLimit-Reset");
                assertThat(resetHeader).isNotNull();
                long resetTime = Long.parseLong(resetHeader);
                assertThat(resetTime).isGreaterThan(System.currentTimeMillis());
            }
        }

        @Test
        @DisplayName("Should show zero remaining when limit exceeded")
        void shouldShowZeroRemaining() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                String expectedKey = "rate_limit:" + TEST_IP;
                MockServerWebExchange exchange = buildExchange("/api/posts");

                stubRedisIncrement(expectedKey, (long) MAX_ANONYMOUS + 5);

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                        .isEqualTo("0");
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Login / password-reset paths
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Login and password-reset rate limits")
    class LoginRateLimits {

        @Test
        @DisplayName("Should use login limit for /auth/login path")
        void shouldUseLoginLimitForAuthLogin() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                String expectedKey = "rate_limit:login:" + TEST_IP;
                MockServerWebExchange exchange = buildExchange("/api/auth/login");

                stubRedisIncrement(expectedKey, 1L);
                when(chain.filter(exchange)).thenReturn(Mono.empty());

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"))
                        .isEqualTo(String.valueOf(MAX_LOGIN));
                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                        .isEqualTo(String.valueOf(MAX_LOGIN - 1));
            }
        }

        @Test
        @DisplayName("Should return 429 when login rate limit exceeded")
        void shouldReturn429WhenLoginLimitExceeded() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                String expectedKey = "rate_limit:login:" + TEST_IP;
                MockServerWebExchange exchange = buildExchange("/api/auth/login");

                stubRedisIncrement(expectedKey, (long) MAX_LOGIN + 1);

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            }
        }

        @Test
        @DisplayName("Should use login limit for forgot-password path")
        void shouldUseLoginLimitForForgotPassword() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                MockServerWebExchange exchange = buildExchange("/api/forgot-password");

                stubRedisIncrementForAnyKey(1L);
                when(chain.filter(exchange)).thenReturn(Mono.empty());

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"))
                        .isEqualTo(String.valueOf(MAX_LOGIN));
            }
        }

        @Test
        @DisplayName("Should use login limit for reset-password path")
        void shouldUseLoginLimitForResetPassword() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                MockServerWebExchange exchange = buildExchange("/api/reset-password");

                stubRedisIncrementForAnyKey(1L);
                when(chain.filter(exchange)).thenReturn(Mono.empty());

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"))
                        .isEqualTo(String.valueOf(MAX_LOGIN));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // In-memory fallback when Redis is unavailable
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("In-memory fallback")
    class InMemoryFallback {

        @Test
        @DisplayName("Should use in-memory fallback when Redis fails")
        void shouldFallbackToInMemoryWhenRedisFails() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                MockServerWebExchange exchange = buildExchange("/api/posts");

                when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
                when(zSetOperations.removeRangeByScore(anyString(), any(Range.class)))
                        .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));
                when(chain.filter(exchange)).thenReturn(Mono.empty());

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                // Should still set rate limit headers via in-memory fallback
                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"))
                        .isEqualTo(String.valueOf(MAX_ANONYMOUS));
                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                        .isEqualTo(String.valueOf(MAX_ANONYMOUS - 1));
                assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            }
        }

        @Test
        @DisplayName("Should enforce in-memory rate limit when fallback triggered")
        void shouldEnforceInMemoryLimitWhenFallbackTriggered() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
                when(zSetOperations.removeRangeByScore(anyString(), any(Range.class)))
                        .thenReturn(Mono.error(new RuntimeException("Redis unavailable")));

                // Simulate MAX_ANONYMOUS + 1 requests to exceed limit
                for (int i = 0; i < MAX_ANONYMOUS; i++) {
                    MockServerWebExchange exchange = buildExchange("/api/posts");
                    when(chain.filter(exchange)).thenReturn(Mono.empty());

                    StepVerifier.create(filter.filter(exchange, chain))
                            .verifyComplete();

                    assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                }

                // The next request should be rejected
                MockServerWebExchange exceededExchange = buildExchange("/api/posts");

                StepVerifier.create(filter.filter(exceededExchange, chain))
                        .verifyComplete();

                assertThat(exceededExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(exceededExchange.getResponse().getHeaders().getFirst("Retry-After"))
                        .isEqualTo(String.valueOf(WINDOW_SECONDS));
            }
        }

        @Test
        @DisplayName("Should add Retry-After header on in-memory limit exceeded")
        void shouldAddRetryAfterOnInMemoryLimit() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
                when(zSetOperations.removeRangeByScore(anyString(), any(Range.class)))
                        .thenReturn(Mono.error(new RuntimeException("Redis down")));

                // Exhaust the limit
                for (int i = 0; i < MAX_ANONYMOUS; i++) {
                    MockServerWebExchange ex = buildExchange("/api/data");
                    when(chain.filter(ex)).thenReturn(Mono.empty());
                    StepVerifier.create(filter.filter(ex, chain)).verifyComplete();
                }

                // Trigger 429
                MockServerWebExchange blocked = buildExchange("/api/data");
                StepVerifier.create(filter.filter(blocked, chain)).verifyComplete();

                assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(blocked.getResponse().getHeaders().getFirst("Retry-After"))
                        .isNotNull()
                        .isEqualTo(String.valueOf(WINDOW_SECONDS));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Scheduled cleanup
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Scheduled cleanup")
    class ScheduledCleanup {

        @Test
        @DisplayName("cleanupExpiredInMemoryEntries should remove expired entries")
        void shouldRemoveExpiredEntries() {
            // Build a filter with a very short window so entries expire quickly
            RateLimitingFilter shortWindowFilter = new RateLimitingFilter(
                    redisTemplate, tokenProvider,
                    MAX_AUTHENTICATED, MAX_ANONYMOUS, MAX_LOGIN,
                    1 // 1-second window
            );

            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
                when(zSetOperations.removeRangeByScore(anyString(), any(Range.class)))
                        .thenReturn(Mono.error(new RuntimeException("Redis unavailable")));

                // Make a request to populate in-memory map via fallback
                MockServerWebExchange exchange = buildExchange("/api/test");
                when(chain.filter(exchange)).thenReturn(Mono.empty());

                StepVerifier.create(shortWindowFilter.filter(exchange, chain))
                        .verifyComplete();

                // Verify entry exists (remaining should be less than max)
                assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                        .isEqualTo(String.valueOf(MAX_ANONYMOUS - 1));

                // Wait for entries to expire
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Run cleanup
                shortWindowFilter.cleanupExpiredInMemoryEntries();

                // Make another request — should start fresh (count = 1 again)
                MockServerWebExchange newExchange = buildExchange("/api/test");
                when(chain.filter(newExchange)).thenReturn(Mono.empty());

                StepVerifier.create(shortWindowFilter.filter(newExchange, chain))
                        .verifyComplete();

                assertThat(newExchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                        .isEqualTo(String.valueOf(MAX_ANONYMOUS - 1));
            }
        }

        @Test
        @DisplayName("cleanupExpiredInMemoryEntries should not remove non-expired entries")
        void shouldNotRemoveNonExpiredEntries() {
            try (MockedStatic<IpAddressExtractor> ipMock = mockStatic(IpAddressExtractor.class)) {
                ipMock.when(() -> IpAddressExtractor.extractClientIp(any(MockServerWebExchange.class)))
                        .thenReturn(TEST_IP);

                when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
                when(zSetOperations.removeRangeByScore(anyString(), any(Range.class)))
                        .thenReturn(Mono.error(new RuntimeException("Redis unavailable")));

                // Populate in-memory entries
                MockServerWebExchange exchange = buildExchange("/api/test");
                when(chain.filter(exchange)).thenReturn(Mono.empty());

                StepVerifier.create(filter.filter(exchange, chain))
                        .verifyComplete();

                // Run cleanup immediately (entry should still be valid with 60s window)
                filter.cleanupExpiredInMemoryEntries();

                // Make another request — count should increment (not reset)
                MockServerWebExchange secondExchange = buildExchange("/api/test");
                when(chain.filter(secondExchange)).thenReturn(Mono.empty());

                StepVerifier.create(filter.filter(secondExchange, chain))
                        .verifyComplete();

                assertThat(secondExchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                        .isEqualTo(String.valueOf(MAX_ANONYMOUS - 2));
            }
        }
    }
}
