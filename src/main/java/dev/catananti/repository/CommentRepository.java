package dev.catananti.repository;

import dev.catananti.entity.Comment;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Repository
public interface CommentRepository extends ReactiveCrudRepository<Comment, Long> {

    @Query("SELECT * FROM comments WHERE article_id = :articleId AND status = 'APPROVED' AND parent_id IS NULL ORDER BY created_at DESC LIMIT :limit")
    Flux<Comment> findApprovedByArticleId(Long articleId, int limit);

    @Query("SELECT * FROM comments WHERE article_id = :articleId AND status = 'APPROVED' AND parent_id IS NULL ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findApprovedByArticleIdPaginated(Long articleId, int limit, int offset);

    @Query("SELECT * FROM comments WHERE article_id = :articleId AND status = 'APPROVED' AND parent_id IS NULL ORDER BY likes_count DESC, created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findApprovedByArticleIdSortedByLikes(Long articleId, int limit, int offset);

    @Query("SELECT * FROM comments WHERE article_id = :articleId AND status = 'APPROVED' AND parent_id IS NULL ORDER BY created_at ASC LIMIT :limit OFFSET :offset")
    Flux<Comment> findApprovedByArticleIdSortedByOldest(Long articleId, int limit, int offset);

    // Q9.1: Batch-load replies for multiple parent IDs to avoid N+1.
    // Must be a Collection: spring-r2dbc only expands Collection parameters into
    // IN lists — an array binds as a single bigint[] and fails on PostgreSQL.
    @Query("SELECT * FROM comments WHERE parent_id IN (:parentIds) AND status = 'APPROVED' ORDER BY created_at ASC")
    Flux<Comment> findApprovedRepliesByParentIds(List<Long> parentIds);

    @Query("SELECT * FROM comments WHERE article_id = :articleId ORDER BY created_at DESC LIMIT :limit")
    Flux<Comment> findAllByArticleId(Long articleId, int limit);

    @Query("SELECT * FROM comments WHERE status = :status ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findByStatus(String status, int limit, int offset);

    @Query("SELECT COUNT(*) FROM comments WHERE status = :status")
    Mono<Long> countByStatus(String status);

    @Query("SELECT * FROM comments ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findAllPaginated(int limit, int offset);

    @Query("SELECT COUNT(*) FROM comments WHERE article_id = :articleId AND status = 'APPROVED'")
    Mono<Long> countApprovedByArticleId(Long articleId);

    Mono<Void> deleteByArticleId(Long articleId);

    Mono<Void> deleteByParentId(Long parentId);

    // Atomic increment likes count
    @Modifying
    @Query("UPDATE comments SET likes_count = COALESCE(likes_count, 0) + 1 WHERE id = :id")
    Mono<Void> incrementLikes(Long id);

    // Atomic decrement likes count (floor at 0)
    @Modifying
    @Query("UPDATE comments SET likes_count = GREATEST(COALESCE(likes_count, 0) - 1, 0) WHERE id = :id")
    Mono<Void> decrementLikes(Long id);

    @Query("SELECT likes_count FROM comments WHERE id = :id")
    Mono<Integer> getLikesCount(Long id);

    @Query("SELECT COUNT(*) FROM comments WHERE LOWER(author_email) = LOWER(:email) AND status = 'APPROVED'")
    Mono<Long> countApprovedByAuthorEmail(String email);

    // ==================== AUTHOR-SCOPED QUERIES (ownership enforcement) ====================

    @Query("SELECT c.* FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId AND c.status = :status ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findByArticleAuthorIdAndStatus(Long authorId, String status, int limit, int offset);

    @Query("SELECT COUNT(c.*) FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId AND c.status = :status")
    Mono<Long> countByArticleAuthorIdAndStatus(Long authorId, String status);

    @Query("SELECT c.* FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findByArticleAuthorId(Long authorId, int limit, int offset);

    @Query("SELECT COUNT(c.*) FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId")
    Mono<Long> countByArticleAuthorId(Long authorId);

    // ==================== ADMIN SEARCH (AUD19C-2) ====================
    // Case-insensitive substring match on comment content OR author name.
    // Callers must escape LIKE wildcards (DigestUtils.escapeLikePattern, F-291);
    // ESCAPE '\' makes the escape character explicit on both PostgreSQL and H2.

    @Query("SELECT * FROM comments WHERE (LOWER(content) LIKE LOWER('%' || :search || '%') ESCAPE '\\' OR LOWER(author_name) LIKE LOWER('%' || :search || '%') ESCAPE '\\') ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findAllBySearch(String search, int limit, int offset);

    @Query("SELECT COUNT(*) FROM comments WHERE (LOWER(content) LIKE LOWER('%' || :search || '%') ESCAPE '\\' OR LOWER(author_name) LIKE LOWER('%' || :search || '%') ESCAPE '\\')")
    Mono<Long> countAllBySearch(String search);

    @Query("SELECT * FROM comments WHERE status = :status AND (LOWER(content) LIKE LOWER('%' || :search || '%') ESCAPE '\\' OR LOWER(author_name) LIKE LOWER('%' || :search || '%') ESCAPE '\\') ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findByStatusAndSearch(String status, String search, int limit, int offset);

    @Query("SELECT COUNT(*) FROM comments WHERE status = :status AND (LOWER(content) LIKE LOWER('%' || :search || '%') ESCAPE '\\' OR LOWER(author_name) LIKE LOWER('%' || :search || '%') ESCAPE '\\')")
    Mono<Long> countByStatusAndSearch(String status, String search);

    @Query("SELECT c.* FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId AND (LOWER(c.content) LIKE LOWER('%' || :search || '%') ESCAPE '\\' OR LOWER(c.author_name) LIKE LOWER('%' || :search || '%') ESCAPE '\\') ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findByArticleAuthorIdAndSearch(Long authorId, String search, int limit, int offset);

    @Query("SELECT COUNT(c.*) FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId AND (LOWER(c.content) LIKE LOWER('%' || :search || '%') ESCAPE '\\' OR LOWER(c.author_name) LIKE LOWER('%' || :search || '%') ESCAPE '\\')")
    Mono<Long> countByArticleAuthorIdAndSearch(Long authorId, String search);

    @Query("SELECT c.* FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId AND c.status = :status AND (LOWER(c.content) LIKE LOWER('%' || :search || '%') ESCAPE '\\' OR LOWER(c.author_name) LIKE LOWER('%' || :search || '%') ESCAPE '\\') ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findByArticleAuthorIdAndStatusAndSearch(Long authorId, String status, String search, int limit, int offset);

    @Query("SELECT COUNT(c.*) FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId AND c.status = :status AND (LOWER(c.content) LIKE LOWER('%' || :search || '%') ESCAPE '\\' OR LOWER(c.author_name) LIKE LOWER('%' || :search || '%') ESCAPE '\\')")
    Mono<Long> countByArticleAuthorIdAndStatusAndSearch(Long authorId, String status, String search);

    // ==================== ACCOUNT DELETION (V21) ====================

    @Query("SELECT COUNT(*) FROM comments WHERE user_id = :userId")
    Mono<Long> countByUserId(Long userId);

    /**
     * Erasure of the author's PII on public comments. user_id is deliberately
     * KEPT: it points at the anonymized users row, preserving referential
     * integrity and statistics without personal data (LGPD art. 16, IV) — the
     * email string was the only re-identifying elo and it is nulled here.
     */
    /**
     * Erasure of comment PII. Matches by user_id (structural link) and by email
     * as a safety net for rows the V21 backfill missed or that predate user_id
     * propagation — after erasure, no comment may still carry the address.
     * user_id itself is kept on purpose (integrity without re-identification).
     */
    @Modifying
    @Query("UPDATE comments SET author_name = :anonName, author_email = NULL "
         + "WHERE user_id = :userId OR LOWER(author_email) = LOWER(:email)")
    Mono<Long> anonymizeByOwner(Long userId, String email, String anonName);
}
