package dev.catananti.repository;

import dev.catananti.entity.Article;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface ArticleRepository extends ReactiveCrudRepository<Article, Long> {

    Mono<Article> findBySlug(String slug);

    @Query("SELECT * FROM articles WHERE slug = :slug AND status = :status")
    Mono<Article> findBySlugAndStatus(String slug, String status);

    // NP-6: listing queries project every column EXCEPT content — list pages render
    // excerpts, and articles' markdown bodies (20-50KB each) dominated row transfer,
    // JSON size and Redis payloads. content stays null on entities from these queries.
    @Query("SELECT id, version, slug, title, subtitle, excerpt, cover_image_url, author_id, status, published_at, reading_time_minutes, views_count, likes_count, seo_title, seo_description, seo_keywords, scheduled_at, original_locale, created_at, updated_at FROM articles WHERE status = :status ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusOrderByPublishedAtDesc(String status, int limit, int offset);

    @Query("SELECT id, version, slug, title, subtitle, excerpt, cover_image_url, author_id, status, published_at, reading_time_minutes, views_count, likes_count, seo_title, seo_description, seo_keywords, scheduled_at, original_locale, created_at, updated_at FROM articles WHERE status = :status ORDER BY views_count DESC NULLS LAST LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusOrderByViewsCountDesc(String status, int limit, int offset);

    @Query("SELECT * FROM articles ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findAllOrderByCreatedAtDesc(int limit, int offset);

    @Query("SELECT * FROM articles WHERE status = :status ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusOrderByCreatedAtDesc(String status, int limit, int offset);

    @Query("SELECT COUNT(*) FROM articles")
    Mono<Long> countAll();

    @Query("SELECT COUNT(*) FROM articles WHERE status = :status")
    Mono<Long> countByStatus(String status);

    // LIKE-based fallback for H2 dev/test profile (to_tsvector not supported).
    @Query("SELECT id, version, slug, title, subtitle, excerpt, cover_image_url, author_id, status, published_at, reading_time_minutes, views_count, likes_count, seo_title, seo_description, seo_keywords, scheduled_at, original_locale, created_at, updated_at FROM articles WHERE status = :status AND " +
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

    // Admin-facing search — spans ALL statuses (DRAFT/SCHEDULED/REVIEW/ARCHIVED/PUBLISHED),
    // unlike the public search which is PUBLISHED-only. LIKE-based so it works on both
    // Postgres and the H2 test profile.
    @Query("SELECT * FROM articles WHERE " +
           "(LOWER(title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(content) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(excerpt) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> adminSearchByQuery(String query, int limit, int offset);

    @Query("SELECT COUNT(*) FROM articles WHERE " +
           "(LOWER(title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(content) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(excerpt) LIKE LOWER(CONCAT('%', :query, '%')))")
    Mono<Long> countAdminSearchByQuery(String query);

    // Author-scoped admin search — DEV users only see their own articles.
    @Query("SELECT * FROM articles WHERE author_id = :authorId AND " +
           "(LOWER(title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(content) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(excerpt) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> adminSearchByAuthorAndQuery(Long authorId, String query, int limit, int offset);

    @Query("SELECT COUNT(*) FROM articles WHERE author_id = :authorId AND " +
           "(LOWER(title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(content) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(excerpt) LIKE LOWER(CONCAT('%', :query, '%')))")
    Mono<Long> countAdminSearchByAuthorAndQuery(Long authorId, String query);

    // Q4.2: PostgreSQL FTS variants — match against base article OR any article_i18n translation.
    // Uses 'simple' config for language-agnostic matching (PT/ES/IT/EN all work equally).
    // Backed by GIN indexes in V14 (articles) and V16 (article_i18n).
    @Query("""
            SELECT * FROM articles a
            WHERE a.status = :status AND (
                to_tsvector('simple', coalesce(a.title, '') || ' ' || coalesce(a.excerpt, '') || ' ' || coalesce(a.content, ''))
                    @@ plainto_tsquery('simple', :query)
                OR EXISTS (
                    SELECT 1 FROM article_i18n i18n
                    WHERE i18n.article_id = a.id
                      AND to_tsvector('simple', coalesce(i18n.title, '') || ' ' || coalesce(i18n.excerpt, '') || ' ' || coalesce(i18n.content, ''))
                          @@ plainto_tsquery('simple', :query)
                )
            )
            ORDER BY a.published_at DESC LIMIT :limit OFFSET :offset
            """)
    Flux<Article> searchByStatusAndQueryFts(String status, String query, int limit, int offset);

    @Query("""
            SELECT COUNT(*) FROM articles a
            WHERE a.status = :status AND (
                to_tsvector('simple', coalesce(a.title, '') || ' ' || coalesce(a.excerpt, '') || ' ' || coalesce(a.content, ''))
                    @@ plainto_tsquery('simple', :query)
                OR EXISTS (
                    SELECT 1 FROM article_i18n i18n
                    WHERE i18n.article_id = a.id
                      AND to_tsvector('simple', coalesce(i18n.title, '') || ' ' || coalesce(i18n.excerpt, '') || ' ' || coalesce(i18n.content, ''))
                          @@ plainto_tsquery('simple', :query)
                )
            )
            """)
    Mono<Long> countSearchByStatusAndQueryFts(String status, String query);

    @Query("SELECT a.id, a.version, a.slug, a.title, a.subtitle, a.excerpt, a.cover_image_url, a.author_id, a.status, a.published_at, a.reading_time_minutes, a.views_count, a.likes_count, a.seo_title, a.seo_description, a.seo_keywords, a.scheduled_at, a.original_locale, a.created_at, a.updated_at FROM articles a " +
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

    @Query("SELECT id, version, slug, title, subtitle, excerpt, cover_image_url, author_id, status, published_at, reading_time_minutes, views_count, likes_count, seo_title, seo_description, seo_keywords, scheduled_at, original_locale, created_at, updated_at FROM articles WHERE status = 'PUBLISHED' ORDER BY published_at DESC LIMIT :limit")
    Flux<Article> findAllPublishedOrderByPublishedAtDesc(int limit);

    Mono<Boolean> existsBySlug(String slug);

    // Related articles - articles that share tags with the given article
    @Query("""
            SELECT DISTINCT a.id, a.version, a.slug, a.title, a.subtitle, a.excerpt, a.cover_image_url, a.author_id, a.status, a.published_at, a.reading_time_minutes, a.views_count, a.likes_count, a.seo_title, a.seo_description, a.seo_keywords, a.scheduled_at, a.original_locale, a.created_at, a.updated_at FROM articles a
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
    @Query("SELECT id, version, slug, title, subtitle, excerpt, cover_image_url, author_id, status, published_at, reading_time_minutes, views_count, likes_count, seo_title, seo_description, seo_keywords, scheduled_at, original_locale, created_at, updated_at FROM articles WHERE id != :excludeId AND status = 'PUBLISHED' ORDER BY published_at DESC LIMIT :limit")
    Flux<Article> findRecentPublishedExcluding(Long excludeId, int limit);

    // Date-range filtered queries
    @Query("SELECT id, version, slug, title, subtitle, excerpt, cover_image_url, author_id, status, published_at, reading_time_minutes, views_count, likes_count, seo_title, seo_description, seo_keywords, scheduled_at, original_locale, created_at, updated_at FROM articles WHERE status = :status AND published_at >= :dateFrom AND published_at <= :dateTo ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusAndDateRangeOrderByPublishedAtDesc(String status, LocalDateTime dateFrom, LocalDateTime dateTo, int limit, int offset);

    @Query("SELECT COUNT(*) FROM articles WHERE status = :status AND published_at >= :dateFrom AND published_at <= :dateTo")
    Mono<Long> countByStatusAndDateRange(String status, LocalDateTime dateFrom, LocalDateTime dateTo);

    // Find scheduled articles that should be published now
    @Query("SELECT * FROM articles WHERE status = 'SCHEDULED' AND scheduled_at <= :now")
    Flux<Article> findScheduledArticlesToPublish(LocalDateTime now);

    /**
     * Atomically claim a scheduled article for publication (compare-and-swap).
     *
     * <p>The {@code WHERE status = 'SCHEDULED'} predicate guarantees that, even if
     * multiple replicas run the publish scheduler concurrently, exactly one wins the
     * race for a given row (rows affected == 1); all others get 0 and must skip.
     * This makes duplicate publishing — and the duplicate subscriber e-mail blast it
     * would trigger — impossible regardless of the distributed lock's behaviour.</p>
     *
     * @return number of rows updated: 1 if this caller claimed the article, 0 otherwise.
     */
    @Modifying
    @Query("UPDATE articles SET status = 'PUBLISHED', published_at = :now, updated_at = :now " +
           "WHERE id = :id AND status = 'SCHEDULED'")
    Mono<Integer> markPublishedIfScheduled(Long id, LocalDateTime now);

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

    @Query("UPDATE articles SET likes_count = GREATEST(COALESCE(likes_count, 0) - 1, 0) WHERE slug = :slug")
    Mono<Void> decrementLikesBySlug(String slug);

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

    // ==================== SORTABLE ADMIN QUERIES ====================

    @Query("SELECT * FROM articles WHERE status = :status ORDER BY title ASC LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusOrderByTitleAsc(String status, int limit, int offset);

    @Query("SELECT * FROM articles ORDER BY title ASC LIMIT :limit OFFSET :offset")
    Flux<Article> findAllOrderByTitleAsc(int limit, int offset);

    @Query("SELECT * FROM articles WHERE status = :status ORDER BY created_at ASC LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusOrderByCreatedAtAsc(String status, int limit, int offset);

    @Query("SELECT * FROM articles ORDER BY created_at ASC LIMIT :limit OFFSET :offset")
    Flux<Article> findAllOrderByCreatedAtAsc(int limit, int offset);

    @Query("SELECT * FROM articles WHERE status = :status ORDER BY views_count DESC NULLS LAST LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusOrderByViewsDesc(String status, int limit, int offset);

    @Query("SELECT * FROM articles ORDER BY views_count DESC NULLS LAST LIMIT :limit OFFSET :offset")
    Flux<Article> findAllOrderByViewsDesc(int limit, int offset);

    @Query("SELECT * FROM articles WHERE status = :status ORDER BY likes_count DESC NULLS LAST LIMIT :limit OFFSET :offset")
    Flux<Article> findByStatusOrderByLikesDesc(String status, int limit, int offset);

    @Query("SELECT * FROM articles ORDER BY likes_count DESC NULLS LAST LIMIT :limit OFFSET :offset")
    Flux<Article> findAllOrderByLikesDesc(int limit, int offset);

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
