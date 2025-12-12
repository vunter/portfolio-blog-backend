package dev.catananti.controller;

import dev.catananti.entity.UserRole;
import dev.catananti.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Dashboard stats scoped by role:
 * - ADMIN sees global stats (all articles, all users, subscribers, etc.)
 * - DEV/EDITOR sees only their own articles, comments on their articles, tags on their articles.
 *   Users/subscribers stats are omitted (set to 0).
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV', 'EDITOR')")
@Tag(name = "Admin - Dashboard", description = "Dashboard statistics and activity")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminDashboardController {

    private final ArticleRepository articleRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final SubscriberRepository subscriberRepository;
    private final TagRepository tagRepository;

    @GetMapping("/stats")
    @Operation(summary = "Get dashboard statistics", description = "Get overview statistics scoped by role")
    public Mono<Map<String, Object>> getDashboardStats() {
        log.debug("Fetching dashboard stats");
        return getCurrentUser().flatMap(user -> {
            if (UserRole.ADMIN.matches(user.getRole())) {
                return getGlobalStats();
            } else {
                return getScopedStats(user.getId());
            }
        });
    }

    @GetMapping("/activity")
    @Operation(summary = "Get recent activity", description = "Get recent activity feed scoped by role")
    public Mono<List<Map<String, Object>>> getRecentActivity() {
        log.debug("Fetching recent activity");
        return getCurrentUser().flatMap(user -> {
            if (UserRole.ADMIN.matches(user.getRole())) {
                return getGlobalActivity();
            } else {
                return getScopedActivity(user.getId());
            }
        });
    }

    // ==================== GLOBAL STATS (ADMIN) ====================

    private Mono<Map<String, Object>> getGlobalStats() {
        return Mono.zip(
                articleRepository.count(),
                articleRepository.countByStatus("PUBLISHED"),
                articleRepository.countByStatus("DRAFT"),
                commentRepository.count(),
                commentRepository.countByStatus("PENDING"),
                userRepository.count(),
                subscriberRepository.countConfirmed(),
                articleRepository.sumViewsCount()
        ).flatMap(tuple -> tagRepository.count()
                .map(tagCount -> {
                    var stats = new java.util.HashMap<String, Object>();
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

    // ==================== SCOPED STATS (DEV/EDITOR) ====================

    private Mono<Map<String, Object>> getScopedStats(Long authorId) {
        return Mono.zip(
                articleRepository.countByAuthorId(authorId),
                articleRepository.countByAuthorIdAndStatus(authorId, "PUBLISHED"),
                articleRepository.countByAuthorIdAndStatus(authorId, "DRAFT"),
                commentRepository.countByArticleAuthorId(authorId),
                commentRepository.countByArticleAuthorIdAndStatus(authorId, "PENDING"),
                articleRepository.sumViewsCountByAuthorId(authorId),
                tagRepository.countByAuthorId(authorId)
        ).map(tuple -> {
            var stats = new java.util.HashMap<String, Object>();
            stats.put("totalArticles", tuple.getT1());
            stats.put("publishedArticles", tuple.getT2());
            stats.put("draftArticles", tuple.getT3());
            stats.put("totalComments", tuple.getT4());
            stats.put("pendingComments", tuple.getT5());
            stats.put("totalUsers", 0L);               // DEV/EDITOR cannot see user management
            stats.put("newsletterSubscribers", 0L);     // DEV/EDITOR cannot see newsletter
            stats.put("totalViews", tuple.getT6());
            stats.put("totalTags", tuple.getT7());
            stats.put("timestamp", LocalDateTime.now().toString());
            return (Map<String, Object>) stats;
        });
    }

    // ==================== ACTIVITY FEEDS ====================

    private Mono<List<Map<String, Object>>> getGlobalActivity() {
        return articleRepository.findRecentlyUpdated(10)
                .map(this::mapActivityItem)
                .collectList();
    }

    private Mono<List<Map<String, Object>>> getScopedActivity(Long authorId) {
        return articleRepository.findRecentlyUpdatedByAuthorId(authorId, 10)
                .map(this::mapActivityItem)
                .collectList();
    }

    private Map<String, Object> mapActivityItem(dev.catananti.entity.Article article) {
        String action = "PUBLISHED".equals(article.getStatus()) ? "published" : "updated";
        String title = article.getTitle();
        String createdAt = (article.getUpdatedAt() != null ? article.getUpdatedAt() : article.getCreatedAt()).toString();
        return Map.of(
                "id", article.getId(),
                "type", "article",
                "action", action,
                "title", title,
                "description", action + ": " + title,
                "createdAt", createdAt
        );
    }

    // ==================== AUTH HELPER ====================

    private Mono<dev.catananti.entity.User> getCurrentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> auth.getName())
                .flatMap(email -> userRepository.findByEmail(email));
    }
}
