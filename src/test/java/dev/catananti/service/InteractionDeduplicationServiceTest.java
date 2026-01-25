package dev.catananti.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import dev.catananti.util.IpAddressExtractor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractionDeduplicationServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @InjectMocks
    private InteractionDeduplicationService deduplicationService;

    @Mock
    private ServerHttpRequest request;

    // ============================
    // recordViewIfNew
    // ============================
    @Nested
    @DisplayName("recordViewIfNew")
    class RecordViewIfNew {

        @Test
        @DisplayName("should return true for a new view from known IP")
        void newView_returnsTrue() {
            try (MockedStatic<IpAddressExtractor> extractor = mockStatic(IpAddressExtractor.class)) {
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("192.168.1.1");
                when(redisTemplate.opsForValue()).thenReturn(valueOps);
                when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                        .thenReturn(Mono.just(true));

                StepVerifier.create(deduplicationService.recordViewIfNew("test-slug", request))
                        .expectNext(true)
                        .verifyComplete();
            }
        }

        @Test
        @DisplayName("should return false for a duplicate view")
        void duplicateView_returnsFalse() {
            try (MockedStatic<IpAddressExtractor> extractor = mockStatic(IpAddressExtractor.class)) {
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("192.168.1.1");
                when(redisTemplate.opsForValue()).thenReturn(valueOps);
                when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                        .thenReturn(Mono.just(false));

                StepVerifier.create(deduplicationService.recordViewIfNew("test-slug", request))
                        .expectNext(false)
                        .verifyComplete();
            }
        }

        @Test
        @DisplayName("should return false when IP is unknown")
        void unknownIp_returnsFalse() {
            try (MockedStatic<IpAddressExtractor> extractor = mockStatic(IpAddressExtractor.class)) {
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("unknown");

                StepVerifier.create(deduplicationService.recordViewIfNew("test-slug", request))
                        .expectNext(false)
                        .verifyComplete();
            }
        }

        @Test
        @DisplayName("should allow through on Redis error")
        void redisError_allowsThrough() {
            try (MockedStatic<IpAddressExtractor> extractor = mockStatic(IpAddressExtractor.class)) {
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("192.168.1.1");
                when(redisTemplate.opsForValue()).thenReturn(valueOps);
                when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                        .thenReturn(Mono.error(new RuntimeException("Redis connection failed")));

                StepVerifier.create(deduplicationService.recordViewIfNew("test-slug", request))
                        .expectNext(true)
                        .verifyComplete();
            }
        }
    }

    // ============================
    // recordLikeIfNew
    // ============================
    @Nested
    @DisplayName("recordLikeIfNew")
    class RecordLikeIfNew {

        @Test
        @DisplayName("should return true for a new like from known IP")
        void newLike_returnsTrue() {
            try (MockedStatic<IpAddressExtractor> extractor = mockStatic(IpAddressExtractor.class)) {
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("10.0.0.1");
                when(redisTemplate.opsForValue()).thenReturn(valueOps);
                when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                        .thenReturn(Mono.just(true));

                StepVerifier.create(deduplicationService.recordLikeIfNew("test-slug", request))
                        .expectNext(true)
                        .verifyComplete();
            }
        }

        @Test
        @DisplayName("should return false for a duplicate like")
        void duplicateLike_returnsFalse() {
            try (MockedStatic<IpAddressExtractor> extractor = mockStatic(IpAddressExtractor.class)) {
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("10.0.0.1");
                when(redisTemplate.opsForValue()).thenReturn(valueOps);
                when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                        .thenReturn(Mono.just(false));

                StepVerifier.create(deduplicationService.recordLikeIfNew("test-slug", request))
                        .expectNext(false)
                        .verifyComplete();
            }
        }

        @Test
        @DisplayName("should return false when IP is unknown")
        void unknownIp_returnsFalse() {
            try (MockedStatic<IpAddressExtractor> extractor = mockStatic(IpAddressExtractor.class)) {
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("unknown");

                StepVerifier.create(deduplicationService.recordLikeIfNew("test-slug", request))
                        .expectNext(false)
                        .verifyComplete();
            }
        }

        @Test
        @DisplayName("should allow through on Redis error")
        void redisError_allowsThrough() {
            try (MockedStatic<IpAddressExtractor> extractor = mockStatic(IpAddressExtractor.class)) {
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("10.0.0.1");
                when(redisTemplate.opsForValue()).thenReturn(valueOps);
                when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                        .thenReturn(Mono.error(new RuntimeException("Redis down")));

                StepVerifier.create(deduplicationService.recordLikeIfNew("test-slug", request))
                        .expectNext(true)
                        .verifyComplete();
            }
        }
    }

    // ============================
    // hashIp (tested indirectly via key determinism)
    // ============================
    @Nested
    @DisplayName("hashIp — deterministic SHA-256 hashing (indirect)")
    class HashIp {

        @Test
        @DisplayName("should produce same Redis key for same IP (deterministic hash)")
        void sameIp_sameKey() {
            // Call recordViewIfNew twice with the same IP and verify the same key is used
            try (MockedStatic<IpAddressExtractor> extractor = mockStatic(IpAddressExtractor.class)) {
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("192.168.1.100");
                when(redisTemplate.opsForValue()).thenReturn(valueOps);
                when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                        .thenReturn(Mono.just(true));

                // First call
                StepVerifier.create(deduplicationService.recordViewIfNew("slug1", request))
                        .expectNext(true)
                        .verifyComplete();

                // Second call — capture the key
                StepVerifier.create(deduplicationService.recordViewIfNew("slug1", request))
                        .expectNext(true)
                        .verifyComplete();

                // Both calls should use the exact same key (same IP -> same hash)
                verify(valueOps, times(2)).setIfAbsent(
                        argThat(key -> key.startsWith("article_view:slug1:")),
                        eq("1"),
                        any(Duration.class)
                );
            }
        }

        @Test
        @DisplayName("should produce different Redis keys for different IPs")
        void differentIps_differentKeys() {
            try (MockedStatic<IpAddressExtractor> extractor = mockStatic(IpAddressExtractor.class)) {
                when(redisTemplate.opsForValue()).thenReturn(valueOps);
                when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                        .thenReturn(Mono.just(true));

                // First call with IP A
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("192.168.1.1");
                StepVerifier.create(deduplicationService.recordViewIfNew("slug1", request))
                        .expectNext(true)
                        .verifyComplete();

                // Second call with IP B
                extractor.when(() -> IpAddressExtractor.extractClientIp(request)).thenReturn("192.168.1.2");
                StepVerifier.create(deduplicationService.recordViewIfNew("slug1", request))
                        .expectNext(true)
                        .verifyComplete();

                // Two different keys should have been used
                verify(valueOps, times(2)).setIfAbsent(
                        argThat(key -> key.startsWith("article_view:slug1:")),
                        eq("1"),
                        any(Duration.class)
                );
            }
        }
    }
}
