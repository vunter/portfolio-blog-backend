package dev.catananti.service;

import dev.catananti.util.DigestUtils;
import dev.catananti.util.IpAddressExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Service to deduplicate view/like interactions per IP address.
 * Prevents abuse of view/like counts.
 * Only active when Redis is available (production).
 */
@Service
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class InteractionDeduplicationService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    private static final String VIEW_PREFIX = "article_view:";
    private static final String LIKE_PREFIX = "article_like:";
    private static final String COMMENT_LIKE_PREFIX = "comment_like:";
    
    // Views expire after 24 hours (same user can be counted once per day)
    private static final Duration VIEW_TTL = Duration.ofHours(24);
    // Likes expire after 7 days
    private static final Duration LIKE_TTL = Duration.ofDays(7);

    /**
     * Records a view if it's new (not seen from this IP in the TTL period).
     * @return Mono<Boolean> - true if this is a new view, false if duplicate
     */
    public Mono<Boolean> recordViewIfNew(String slug, ServerHttpRequest request) {
        String clientIp = IpAddressExtractor.extractClientIp(request);
        if ("unknown".equals(clientIp)) {
            return Mono.just(false); // Cannot track without IP
        }
        
        String key = VIEW_PREFIX + slug + ":" + hashIp(clientIp);
        
        return redisTemplate.opsForValue()
                .setIfAbsent(key, "1", VIEW_TTL)
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.warn("Failed to check view deduplication: {}", e.getMessage());
                    return Mono.just(true); // Allow on error to not block legitimate users
                });
    }

    /**
     * Records a like if it's new (not seen from this IP in the TTL period).
     * @return Mono<Boolean> - true if this is a new like, false if duplicate
     */
    public Mono<Boolean> recordLikeIfNew(String slug, ServerHttpRequest request) {
        String clientIp = IpAddressExtractor.extractClientIp(request);
        if ("unknown".equals(clientIp)) {
            return Mono.just(false); // Cannot track without IP
        }
        
        String key = LIKE_PREFIX + slug + ":" + hashIp(clientIp);
        
        return redisTemplate.opsForValue()
                .setIfAbsent(key, "1", LIKE_TTL)
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.warn("Failed to check like deduplication: {}", e.getMessage());
                    return Mono.just(true); // Allow on error to not block legitimate users
                });
    }

    /**
     * Removes the like record for this IP, allowing a re-like later.
     * @return Mono<Boolean> - true if the key existed and was removed
     */
    public Mono<Boolean> removeLike(String slug, ServerHttpRequest request) {
        String clientIp = IpAddressExtractor.extractClientIp(request);
        if ("unknown".equals(clientIp)) {
            return Mono.just(false);
        }

        String key = LIKE_PREFIX + slug + ":" + hashIp(clientIp);

        return redisTemplate.delete(key)
                .map(count -> count > 0)
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.warn("Failed to remove like: {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * Checks if the given IP has already liked this article (key exists in Redis).
     */
    public Mono<Boolean> hasLiked(String slug, ServerHttpRequest request) {
        String clientIp = IpAddressExtractor.extractClientIp(request);
        if ("unknown".equals(clientIp)) {
            return Mono.just(false);
        }

        String key = LIKE_PREFIX + slug + ":" + hashIp(clientIp);

        return redisTemplate.hasKey(key)
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.warn("Failed to check like status: {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    // ==================== COMMENT LIKES ====================

    public Mono<Boolean> hasLikedComment(Long commentId, ServerHttpRequest request) {
        String clientIp = IpAddressExtractor.extractClientIp(request);
        if ("unknown".equals(clientIp)) return Mono.just(false);
        String key = COMMENT_LIKE_PREFIX + commentId + ":" + hashIp(clientIp);
        return redisTemplate.hasKey(key).defaultIfEmpty(false)
                .onErrorResume(e -> Mono.just(false));
    }

    public Mono<Boolean> recordCommentLikeIfNew(Long commentId, ServerHttpRequest request) {
        String clientIp = IpAddressExtractor.extractClientIp(request);
        if ("unknown".equals(clientIp)) return Mono.just(false);
        String key = COMMENT_LIKE_PREFIX + commentId + ":" + hashIp(clientIp);
        return redisTemplate.opsForValue().setIfAbsent(key, "1", LIKE_TTL)
                .defaultIfEmpty(false)
                .onErrorResume(e -> Mono.just(true));
    }

    public Mono<Boolean> removeCommentLike(Long commentId, ServerHttpRequest request) {
        String clientIp = IpAddressExtractor.extractClientIp(request);
        if ("unknown".equals(clientIp)) return Mono.just(false);
        String key = COMMENT_LIKE_PREFIX + commentId + ":" + hashIp(clientIp);
        return redisTemplate.delete(key).map(count -> count > 0)
                .defaultIfEmpty(false)
                .onErrorResume(e -> Mono.just(false));
    }

    /**
     * Hash IP for privacy using SHA-256 (don't store raw IPs in Redis).
     */
    private String hashIp(String ip) {
        return DigestUtils.sha256Hex(ip, 16);
    }
}
