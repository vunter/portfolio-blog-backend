package dev.catananti.repository;

import dev.catananti.entity.Bookmark;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface BookmarkRepository extends ReactiveCrudRepository<Bookmark, Long> {

    // RQ-10: no JOIN — it filtered nothing (bookmarks.article_id is a FK) and only
    // added cost; it also disagreed with the plain COUNT below, skewing pagination.
    @Query("SELECT * FROM bookmarks WHERE visitor_hash = :visitorHash ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Bookmark> findByVisitorHash(String visitorHash, int limit, int offset);

    @Query("SELECT COUNT(*) FROM bookmarks WHERE visitor_hash = :visitorHash")
    Mono<Long> countByVisitorHash(String visitorHash);

    Mono<Bookmark> findByArticleIdAndVisitorHash(Long articleId, String visitorHash);

    Mono<Void> deleteByArticleIdAndVisitorHash(Long articleId, String visitorHash);

    @Query("SELECT * FROM bookmarks WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Bookmark> findByUserId(Long userId, int limit, int offset);

    @Query("SELECT COUNT(*) FROM bookmarks WHERE user_id = :userId")
    Mono<Long> countByUserId(Long userId);

    // Account deletion: bookmarks are private data with no public value.
    @Query("DELETE FROM bookmarks WHERE user_id = :userId")
    @org.springframework.data.r2dbc.repository.Modifying
    Mono<Long> deleteByUserId(Long userId);
}
