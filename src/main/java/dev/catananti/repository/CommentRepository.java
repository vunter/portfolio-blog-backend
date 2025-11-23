package dev.catananti.repository;

import dev.catananti.entity.Comment;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// TODO F-289: Add pagination default limit to prevent unbounded result sets on findAllByArticleId
@Repository
public interface CommentRepository extends ReactiveCrudRepository<Comment, Long> {

    @Query("SELECT * FROM comments WHERE article_id = :articleId AND status = 'APPROVED' AND parent_id IS NULL ORDER BY created_at DESC")
    Flux<Comment> findApprovedByArticleId(Long articleId);

    @Query("SELECT * FROM comments WHERE article_id = :articleId AND status = 'APPROVED' AND parent_id IS NULL ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findApprovedByArticleIdPaginated(Long articleId, int limit, int offset);

    @Query("SELECT * FROM comments WHERE parent_id = :parentId AND status = 'APPROVED' ORDER BY created_at ASC")
    Flux<Comment> findApprovedRepliesByParentId(Long parentId);

    @Query("SELECT * FROM comments WHERE article_id = :articleId ORDER BY created_at DESC")
    Flux<Comment> findAllByArticleId(Long articleId);

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

    // ==================== AUTHOR-SCOPED QUERIES (ownership enforcement) ====================

    @Query("SELECT c.* FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId AND c.status = :status ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findByArticleAuthorIdAndStatus(Long authorId, String status, int limit, int offset);

    @Query("SELECT COUNT(c.*) FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId AND c.status = :status")
    Mono<Long> countByArticleAuthorIdAndStatus(Long authorId, String status);

    @Query("SELECT c.* FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Comment> findByArticleAuthorId(Long authorId, int limit, int offset);

    @Query("SELECT COUNT(c.*) FROM comments c JOIN articles a ON c.article_id = a.id WHERE a.author_id = :authorId")
    Mono<Long> countByArticleAuthorId(Long authorId);
}
