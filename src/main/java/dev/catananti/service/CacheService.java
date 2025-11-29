package dev.catananti.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Service for managing cache invalidation strategies.
 * Provides fine-grained control over Redis cache invalidation.
 * TODO F-238: Add cache metrics (hit/miss ratio) for monitoring and tuning
 */
@Service
@Slf4j
public class CacheService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheService(
            @org.springframework.beans.factory.annotation.Autowired(required = false) 
            ReactiveRedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private boolean isRedisAvailable() {
        return redisTemplate != null;
    }

    /**
     * Execute a Redis operation with automatic availability guard.
     * Returns the fallback value when Redis is unavailable.
     */
    private <T> Mono<T> withRedis(T fallback, java.util.function.Supplier<Mono<T>> operation) {
        return isRedisAvailable() ? operation.get() : Mono.just(fallback);
    }

    // Cache key prefixes
    private static final String ARTICLES_CACHE_PREFIX = "articles::";
    private static final String TAGS_CACHE_PREFIX = "tags::";
    private static final String COMMENTS_CACHE_PREFIX = "comments::";
    private static final String SEARCH_CACHE_PREFIX = "search::";
    private static final String FEED_CACHE_PREFIX = "feed::";
    
    // ==================== GENERIC CACHE OPERATIONS ====================

    /**
     * Get a value from cache with automatic deserialization.
     */
    public <T> Mono<T> get(String key, Class<T> type) {
        if (!isRedisAvailable()) return Mono.empty();
        return redisTemplate.opsForValue().get(key)
                .map(value -> {
                    try {
                        if (type.isInstance(value)) {
                            return type.cast(value);
                        }
                        // If value is a String and we need a String, return directly
                        if (type == String.class && value instanceof String s) {
                            return type.cast(s);
                        }
                        // Deserialize from JSON if needed
                        return objectMapper.convertValue(value, type);
                    } catch (Exception e) {
                        log.warn("Failed to deserialize cached value for key {}: {}", key, e.getMessage());
                        return null;
                    }
                })
                .filter(v -> v != null)
                .doOnNext(v -> log.trace("Cache hit for key: {}", key));
    }

    /**
     * Set a value in cache with TTL.
     */
    public <T> Mono<Boolean> set(String key, T value, Duration ttl) {
        return withRedis(false, () -> redisTemplate.opsForValue().set(key, value, ttl)
                .doOnSuccess(success -> {
                    if (success) {
                        log.trace("Cached value for key: {} with TTL: {}", key, ttl);
                    }
                }));
    }

    /**
     * Delete a specific key from cache.
     */
    public Mono<Boolean> delete(String key) {
        return withRedis(false, () -> redisTemplate.delete(key)
                .map(count -> count > 0)
                .doOnSuccess(deleted -> {
                    if (deleted) {
                        log.trace("Deleted cache key: {}", key);
                    }
                }));
    }
    
    // ==================== DOMAIN-SPECIFIC CACHE OPERATIONS ====================

    /**
     * Invalidate all articles cache entries.
     */
    public Mono<Long> invalidateAllArticles() {
        return withRedis(0L, () -> deleteByPattern(ARTICLES_CACHE_PREFIX + "*")
                .doOnSuccess(count -> log.info("Invalidated {} article cache entries", count)));
    }

    /**
     * Invalidate cache for a specific article by slug.
     */
    public Mono<Long> invalidateArticle(String slug) {
        return withRedis(0L, () -> Mono.zip(
                deleteByPattern(ARTICLES_CACHE_PREFIX + "slug_" + slug),
                deleteByPattern(ARTICLES_CACHE_PREFIX + "related_" + slug + "*"),
                deleteByPattern(ARTICLES_CACHE_PREFIX + "published_page_*")
        ).map(tuple -> tuple.getT1() + tuple.getT2() + tuple.getT3())
         .doOnSuccess(count -> log.info("Invalidated cache for article: {}", slug)));
    }

    /**
     * Invalidate cache for articles by tag.
     */
    public Mono<Long> invalidateArticlesByTag(String tagSlug) {
        return withRedis(0L, () -> deleteByPattern(ARTICLES_CACHE_PREFIX + "tag_" + tagSlug + "*")
                .doOnSuccess(count -> log.info("Invalidated {} cache entries for tag: {}", count, tagSlug)));
    }

    /**
     * Invalidate all search cache entries.
     */
    public Mono<Long> invalidateSearchCache() {
        return withRedis(0L, () -> deleteByPattern(SEARCH_CACHE_PREFIX + "*")
                .doOnSuccess(count -> log.info("Invalidated {} search cache entries", count)));
    }

    /**
     * Invalidate all tags cache entries.
     */
    public Mono<Long> invalidateAllTags() {
        return withRedis(0L, () -> deleteByPattern(TAGS_CACHE_PREFIX + "*")
                .doOnSuccess(count -> log.info("Invalidated {} tag cache entries", count)));
    }

    /**
     * Invalidate comments cache for a specific article.
     */
    public Mono<Long> invalidateComments(String articleId) {
        return withRedis(0L, () -> deleteByPattern(COMMENTS_CACHE_PREFIX + articleId + "*")
                .doOnSuccess(count -> log.info("Invalidated {} comment cache entries for article: {}", count, articleId)));
    }

    /**
     * Invalidate all comments cache.
     */
    public Mono<Long> invalidateAllComments() {
        return withRedis(0L, () -> deleteByPattern(COMMENTS_CACHE_PREFIX + "*")
                .doOnSuccess(count -> log.info("Invalidated {} comment cache entries", count)));
    }

    /**
     * Invalidate feed caches (RSS, Sitemap).
     */
    public Mono<Long> invalidateFeedCache() {
        return withRedis(0L, () -> deleteByPattern(FEED_CACHE_PREFIX + "*")
                .doOnSuccess(count -> log.info("Invalidated {} feed cache entries", count)));
    }

    /**
     * Invalidate all caches.
     */
    public Mono<Long> invalidateAllCaches() {
        return withRedis(0L, () -> Mono.zip(
                invalidateAllArticles(),
                invalidateAllTags(),
                invalidateAllComments(),
                invalidateSearchCache(),
                invalidateFeedCache()
        ).map(tuple -> tuple.getT1() + tuple.getT2() + tuple.getT3() + tuple.getT4() + tuple.getT5())
         .doOnSuccess(count -> log.info("Invalidated all caches: {} total entries", count)));
    }

    /**
     * Get cache statistics.
     */
    public Mono<CacheStats> getCacheStats() {
        return withRedis(new CacheStats(0, 0, 0, 0, 0), () -> Mono.zip(
                countByPattern(ARTICLES_CACHE_PREFIX + "*"),
                countByPattern(TAGS_CACHE_PREFIX + "*"),
                countByPattern(COMMENTS_CACHE_PREFIX + "*"),
                countByPattern(SEARCH_CACHE_PREFIX + "*"),
                countByPattern(FEED_CACHE_PREFIX + "*")
        ).map(tuple -> new CacheStats(
                tuple.getT1(),
                tuple.getT2(),
                tuple.getT3(),
                tuple.getT4(),
                tuple.getT5()
        )));
    }

    /**
     * Delete keys matching a pattern using SCAN (non-blocking).
     */
    // TODO F-161: Use flux streaming or limit SCAN count to prevent OOM on large keyspaces
    private Mono<Long> deleteByPattern(String pattern) {
        return redisTemplate.scan(ScanOptions.scanOptions().match(pattern).count(100).build())
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        return Mono.just(0L);
                    }
                    return redisTemplate.delete(keys.toArray(new String[0]));
                });
    }

    /**
     * Count keys matching a pattern using SCAN (non-blocking).
     */
    private Mono<Long> countByPattern(String pattern) {
        return redisTemplate.scan(ScanOptions.scanOptions().match(pattern).count(100).build()).count();
    }

    /**
     * Cache statistics record.
     */
    public record CacheStats(
            long articlesCount,
            long tagsCount,
            long commentsCount,
            long searchCount,
            long feedCount
    ) {
        public long total() {
            return articlesCount + tagsCount + commentsCount + searchCount + feedCount;
        }
    }
}
