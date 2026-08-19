package dev.catananti.service;

import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.CommentStatus;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the dashboard statistics/activity aggregation that previously lived inline in
 * {@code AdminDashboardController}. Keeping it here restores the controller→service→repository
 * layering: the controller only resolves the caller and delegates, while this service holds the
 * cross-repository {@code Mono.zip} aggregation logic.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ArticleRepository articleRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final SubscriberRepository subscriberRepository;
    private final TagRepository tagRepository;

    // ==================== GLOBAL STATS (ADMIN) ====================

    public Mono<Map<String, Object>> getGlobalStats() {
        return Mono.zip(
                articleRepository.count(),
                articleRepository.countByStatus(ArticleStatus.PUBLISHED.name()),
                articleRepository.countByStatus(ArticleStatus.DRAFT.name()),
                commentRepository.count(),
                commentRepository.countByStatus(CommentStatus.PENDING.name()),
                userRepository.count(),
                subscriberRepository.countConfirmed(),
                articleRepository.sumViewsCount()
        ).flatMap(tuple -> tagRepository.count()
                .map(tagCount -> {
                    var stats = new HashMap<String, Object>();
                    stats.put("totalArticles", tuple.getT1());
                    stats.put("publishedArticles", tuple.getT2());
                    stats.put("draftArticles", tuple.getT3());
                    stats.put("totalComments", tuple.getT4());
                    stats.put("pendingComments", tuple.getT5());
                    stats.put("totalUsers", tuple.getT6());
                    stats.put("newsletterSubscribers", tuple.getT7());
                    stats.put("totalViews", tuple.getT8());
                    stats.put("totalTags", tagCount);
                    stats.put("timestamp", LocalDateTime.now().toString());
                    return (Map<String, Object>) stats;
                }));
    }

    // ==================== SCOPED STATS (DEV) ====================

    public Mono<Map<String, Object>> getScopedStats(Long authorId) {
        return Mono.zip(
                articleRepository.countByAuthorId(authorId),
                articleRepository.countByAuthorIdAndStatus(authorId, ArticleStatus.PUBLISHED.name()),
                articleRepository.countByAuthorIdAndStatus(authorId, ArticleStatus.DRAFT.name()),
                commentRepository.countByArticleAuthorId(authorId),
                commentRepository.countByArticleAuthorIdAndStatus(authorId, CommentStatus.PENDING.name()),
                articleRepository.sumViewsCountByAuthorId(authorId),
                tagRepository.countByAuthorId(authorId)
        ).map(tuple -> {
            var stats = new HashMap<String, Object>();
            stats.put("totalArticles", tuple.getT1());
            stats.put("publishedArticles", tuple.getT2());
            stats.put("draftArticles", tuple.getT3());
            stats.put("totalComments", tuple.getT4());
            stats.put("pendingComments", tuple.getT5());
            stats.put("totalUsers", 0L);               // DEV cannot see user management
            stats.put("newsletterSubscribers", 0L);     // DEV cannot see newsletter
            stats.put("totalViews", tuple.getT6());
            stats.put("totalTags", tuple.getT7());
            stats.put("timestamp", LocalDateTime.now().toString());
            return (Map<String, Object>) stats;
        });
    }

    // ==================== ACTIVITY FEEDS ====================

    public Mono<List<Map<String, Object>>> getGlobalActivity() {
        return articleRepository.findRecentlyUpdated(10)
                .map(this::mapActivityItem)
                .collectList();
    }

    public Mono<List<Map<String, Object>>> getScopedActivity(Long authorId) {
        return articleRepository.findRecentlyUpdatedByAuthorId(authorId, 10)
                .map(this::mapActivityItem)
                .collectList();
    }

    private Map<String, Object> mapActivityItem(Article article) {
        String action = article.getStatus() == ArticleStatus.PUBLISHED ? "published" : "updated";
        String title = article.getTitle();
        String createdAt = (article.getUpdatedAt() != null ? article.getUpdatedAt() : article.getCreatedAt()).toString();
        return Map.of(
                // AUD19C-SNOW: stringify Snowflake id — Longs above 2^53 get mangled by JS
                "id", String.valueOf(article.getId()),
                "type", "article",
                "action", action,
                "title", title,
                "description", action + ": " + title,
                "createdAt", createdAt
        );
    }
}
