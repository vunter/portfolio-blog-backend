package dev.catananti.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, Object> valueOperations;

    private ObjectMapper objectMapper;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        cacheService = new CacheService(redisTemplate, objectMapper);
    }

    // ==================== Generic operations ====================

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("Should return cached value on hit")
        void shouldReturnCachedValueOnHit() {
            // Given
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("my-key")).thenReturn(Mono.just("cached-value"));

            // When & Then
            StepVerifier.create(cacheService.get("my-key", String.class))
                    .expectNext("cached-value")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty on cache miss")
        void shouldReturnEmptyOnCacheMiss() {
            // Given
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get("missing-key")).thenReturn(Mono.empty());

            // When & Then
            StepVerifier.create(cacheService.get("missing-key", String.class))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty when Redis is unavailable")
        void shouldReturnEmptyWhenRedisUnavailable() {
            // Given — null redisTemplate
            CacheService noRedisService = new CacheService(null, objectMapper);

            // When & Then
            StepVerifier.create(noRedisService.get("any-key", String.class))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("set")
    class Set {

        @Test
        @DisplayName("Should set value with TTL")
        void shouldSetValueWithTtl() {
            // Given
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.set("key", "value", Duration.ofMinutes(10)))
                    .thenReturn(Mono.just(true));

            // When & Then
            StepVerifier.create(cacheService.set("key", "value", Duration.ofMinutes(10)))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when Redis is unavailable")
        void shouldReturnFalseWhenRedisUnavailable() {
            CacheService noRedisService = new CacheService(null, objectMapper);

            StepVerifier.create(noRedisService.set("key", "value", Duration.ofMinutes(5)))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("Should delete key successfully")
        void shouldDeleteKeySuccessfully() {
            // Given
            when(redisTemplate.delete("key")).thenReturn(Mono.just(1L));

            // When & Then
            StepVerifier.create(cacheService.delete("key"))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when key does not exist")
        void shouldReturnFalseWhenKeyNotFound() {
            // Given
            when(redisTemplate.delete("missing")).thenReturn(Mono.just(0L));

            // When & Then
            StepVerifier.create(cacheService.delete("missing"))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when Redis is unavailable")
        void shouldReturnFalseWhenRedisUnavailable() {
            CacheService noRedisService = new CacheService(null, objectMapper);

            StepVerifier.create(noRedisService.delete("key"))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    // ==================== Domain-specific operations ====================

    @Nested
    @DisplayName("Domain invalidation methods")
    class DomainInvalidation {

        @BeforeEach
        void setUpScan() {
            // Default: SCAN returns empty list
            when(redisTemplate.scan(any())).thenReturn(Flux.empty());
        }

        @Test
        @DisplayName("invalidateAllArticles should scan and delete article keys")
        void invalidateAllArticles() {
            when(redisTemplate.scan(any())).thenReturn(Flux.just("articles::page_1", "articles::slug_test"));
            when(redisTemplate.delete(any(String[].class))).thenReturn(Mono.just(2L));

            StepVerifier.create(cacheService.invalidateAllArticles())
                    .assertNext(count -> assertThat(count).isEqualTo(2L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("invalidateArticle should scan and delete specific article keys")
        void invalidateArticle() {
            // Each of the 3 deleteByPattern calls scans independently
            when(redisTemplate.scan(any())).thenReturn(Flux.empty());

            StepVerifier.create(cacheService.invalidateArticle("my-slug"))
                    .assertNext(count -> assertThat(count).isZero())
                    .verifyComplete();
        }

        @Test
        @DisplayName("invalidateAllTags should complete")
        void invalidateAllTags() {
            StepVerifier.create(cacheService.invalidateAllTags())
                    .assertNext(count -> assertThat(count).isZero())
                    .verifyComplete();
        }

        @Test
        @DisplayName("invalidateAllComments should complete")
        void invalidateAllComments() {
            StepVerifier.create(cacheService.invalidateAllComments())
                    .assertNext(count -> assertThat(count).isZero())
                    .verifyComplete();
        }

        @Test
        @DisplayName("invalidateSearchCache should complete")
        void invalidateSearchCache() {
            StepVerifier.create(cacheService.invalidateSearchCache())
                    .assertNext(count -> assertThat(count).isZero())
                    .verifyComplete();
        }

        @Test
        @DisplayName("invalidateFeedCache should complete")
        void invalidateFeedCache() {
            StepVerifier.create(cacheService.invalidateFeedCache())
                    .assertNext(count -> assertThat(count).isZero())
                    .verifyComplete();
        }

        @Test
        @DisplayName("invalidateAllCaches should combine all domain invalidations")
        void invalidateAllCaches() {
            StepVerifier.create(cacheService.invalidateAllCaches())
                    .assertNext(count -> assertThat(count).isZero())
                    .verifyComplete();
        }
    }

    // ==================== getCacheStats ====================

    @Nested
    @DisplayName("getCacheStats")
    class GetCacheStats {

        @Test
        @DisplayName("Should return cache statistics")
        void shouldReturnCacheStats() {
            // Given — scan returns different counts for each prefix
            when(redisTemplate.scan(any()))
                    .thenReturn(Flux.just("articles::1", "articles::2")) // articles: 2
                    .thenReturn(Flux.just("tags::1"))                    // tags: 1
                    .thenReturn(Flux.empty())                            // comments: 0
                    .thenReturn(Flux.just("search::q1"))                 // search: 1
                    .thenReturn(Flux.empty());                           // feed: 0

            // When & Then
            StepVerifier.create(cacheService.getCacheStats())
                    .assertNext(stats -> {
                        assertThat(stats.articlesCount()).isEqualTo(2);
                        assertThat(stats.tagsCount()).isEqualTo(1);
                        assertThat(stats.commentsCount()).isZero();
                        assertThat(stats.searchCount()).isEqualTo(1);
                        assertThat(stats.feedCount()).isZero();
                        assertThat(stats.total()).isEqualTo(4);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return zeroed stats when Redis is unavailable")
        void shouldReturnZeroStatsWhenRedisUnavailable() {
            CacheService noRedisService = new CacheService(null, objectMapper);

            StepVerifier.create(noRedisService.getCacheStats())
                    .assertNext(stats -> {
                        assertThat(stats.articlesCount()).isZero();
                        assertThat(stats.total()).isZero();
                    })
                    .verifyComplete();
        }
    }

    // ==================== Redis unavailable for domain ops ====================

    @Nested
    @DisplayName("Redis unavailable — domain operations")
    class RedisUnavailableDomainOps {

        private CacheService noRedisService;

        @BeforeEach
        void setUp() {
            noRedisService = new CacheService(null, objectMapper);
        }

        @Test
        @DisplayName("invalidateAllArticles should return 0 when Redis unavailable")
        void invalidateAllArticles() {
            StepVerifier.create(noRedisService.invalidateAllArticles())
                    .expectNext(0L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("invalidateArticle should return 0 when Redis unavailable")
        void invalidateArticle() {
            StepVerifier.create(noRedisService.invalidateArticle("test"))
                    .expectNext(0L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("invalidateAllCaches should return 0 when Redis unavailable")
        void invalidateAllCaches() {
            StepVerifier.create(noRedisService.invalidateAllCaches())
                    .expectNext(0L)
                    .verifyComplete();
        }
    }
}
