package dev.catananti.repository;

import dev.catananti.entity.Article;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface ArticleRepository extends ReactiveCrudRepository<Article, Long> {

    Mono<Article> findBySlug(String slug);

    Mono<Article> findBySlugAndStatus(String slug, String status);

    @Query("SELECT * FROM articles WHERE status = :status ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusOrderByPublishedAtDesc(String status, int limit, int offset);

    @Query("SELECT * FROM articles WHERE status = :status ORDER BY views_count DESC NULLS LAST LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusOrderByViewsCountDesc(String status, int limit, int offset);

    @Query("SELECT * FROM articles ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findAllOrderByCreatedAtDesc(int limit, int offset);

    @Query("SELECT * FROM articles WHERE status = :status ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusOrderByCreatedAtDesc(String status, int limit, int offset);

    @Query("SELECT COUNT(*) FROM articles")
    Mono<Long> countAll();

    @Query("SELECT COUNT(*) FROM articles WHERE status = :status")
    Mono<Long> countByStatus(String status);

    @Query("SELECT * FROM articles WHERE status = :status AND " +
           "(LOWER(title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(content) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(excerpt) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> searchByStatusAndQuery(String status, String query, int limit, int offset);

    @Query("SELECT COUNT(*) FROM articles WHERE status = :status AND " +
           "(LOWER(title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(content) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(excerpt) LIKE LOWER(CONCAT('%', :query, '%')))")
    Mono<Long> countSearchByStatusAndQuery(String status, String query);

    @Query("SELECT a.* FROM articles a " +
           "JOIN article_tags at ON a.id = at.article_id " +
           "JOIN tags t ON at.tag_id = t.id " +
           "WHERE t.slug = :tagSlug AND a.status = :status " +
           "ORDER BY a.published_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findByTagSlugAndStatus(String tagSlug, String status, int limit, int offset);

    @Query("SELECT COUNT(DISTINCT a.id) FROM articles a " +
           "JOIN article_tags at ON a.id = at.article_id " +
           "JOIN tags t ON at.tag_id = t.id " +
           "WHERE t.slug = :tagSlug AND a.status = :status")
    Mono<Long> countByTagSlugAndStatus(String tagSlug, String status);

    @Query("SELECT * FROM articles WHERE status = 'PUBLISHED' ORDER BY published_at DESC")
    Flux<Article> findAllPublishedOrderByPublishedAtDesc();

    Mono<Boolean> existsBySlug(String slug);

    // Related articles - articles that share tags with the given article
    @Query("""
            SELECT DISTINCT a.* FROM articles a
            JOIN article_tags at ON a.id = at.article_id
            WHERE a.id != :articleId
            AND a.status = 'PUBLISHED'
            AND at.tag_id IN (
                SELECT tag_id FROM article_tags WHERE article_id = :articleId
            )
            ORDER BY a.published_at DESC
            LIMIT :limit
            """)
    Flux<Article> findRelatedArticles(Long articleId, int limit);

    // Find recent published articles excluding specific article
    @Query("SELECT * FROM articles WHERE id != :excludeId AND status = 'PUBLISHED' ORDER BY published_at DESC LIMIT :limit")
    Flux<Article> findRecentPublishedExcluding(Long excludeId, int limit);

    // Date-range filtered queries
    @Query("SELECT * FROM articles WHERE status = :status AND published_at >= :dateFrom AND published_at <= :dateTo ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusAndDateRangeOrderByPublishedAtDesc(String status, LocalDateTime dateFrom, LocalDateTime dateTo, int limit, int offset);

    @Query("SELECT COUNT(*) FROM articles WHERE status = :status AND published_at >= :dateFrom AND published_at <= :dateTo")
    Mono<Long> countByStatusAndDateRange(String status, LocalDateTime dateFrom, LocalDateTime dateTo);

    // Find scheduled articles that should be published now
    @Query("SELECT * FROM articles WHERE status = 'SCHEDULED' AND scheduled_at <= :now")
    Flux<Article> findScheduledArticlesToPublish(LocalDateTime now);

    // Count scheduled articles
    @Query("SELECT COUNT(*) FROM articles WHERE status = 'SCHEDULED'")
    Mono<Long> countScheduled();

    // Find top articles by views count (for cache warming)
    @Query("SELECT * FROM articles WHERE status = 'PUBLISHED' ORDER BY views_count DESC NULLS LAST LIMIT :limit")
    Flux<Article> findTopByViewsCount(int limit);

    // Find top articles by likes count
    @Query("SELECT * FROM articles WHERE status = 'PUBLISHED' ORDER BY likes_count DESC NULLS LAST LIMIT :limit")
    Flux<Article> findTopByLikesCount(int limit);

    // Atomic increment views count to avoid race conditions
    @Query("UPDATE articles SET views_count = COALESCE(views_count, 0) + 1 WHERE slug = :slug")
    Mono<Void> incrementViewsBySlug(String slug);

    // Atomic increment likes count to avoid race conditions
    @Query("UPDATE articles SET likes_count = COALESCE(likes_count, 0) + 1 WHERE slug = :slug")
    Mono<Void> incrementLikesBySlug(String slug);

    // Aggregate queries for dashboard stats (avoid loading all articles into memory)
    @Query("SELECT COALESCE(SUM(views_count), 0) FROM articles")
    Mono<Long> sumViewsCount();

    @Query("SELECT COALESCE(SUM(likes_count), 0) FROM articles")
    Mono<Long> sumLikesCount();

    @Query("SELECT COUNT(*) FROM articles WHERE created_at >= :since")
    Mono<Long> countRecentArticles(LocalDateTime since);

    // Recent articles ordered by latest update, for admin dashboard activity feed
    @Query("SELECT * FROM articles ORDER BY COALESCE(updated_at, created_at) DESC LIMIT :limit")
    Flux<Article> findRecentlyUpdated(int limit);

    // ==================== AUTHOR-SCOPED QUERIES (ownership enforcement) ====================

    @Query("SELECT * FROM articles WHERE author_id = :authorId ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findByAuthorIdOrderByCreatedAtDesc(Long authorId, int limit, int offset);

    @Query("SELECT * FROM articles WHERE author_id = :authorId AND status = :status ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findByAuthorIdAndStatusOrderByCreatedAtDesc(Long authorId, String status, int limit, int offset);

    @Query("SELECT COUNT(*) FROM articles WHERE author_id = :authorId")
    Mono<Long> countByAuthorId(Long authorId);

    @Query("SELECT COUNT(*) FROM articles WHERE author_id = :authorId AND status = :status")
    Mono<Long> countByAuthorIdAndStatus(Long authorId, String status);

    @Query("SELECT COALESCE(SUM(views_count), 0) FROM articles WHERE author_id = :authorId")
    Mono<Long> sumViewsCountByAuthorId(Long authorId);

    @Query("SELECT * FROM articles WHERE author_id = :authorId ORDER BY COALESCE(updated_at, created_at) DESC LIMIT :limit")
    Flux<Article> findRecentlyUpdatedByAuthorId(Long authorId, int limit);
}
