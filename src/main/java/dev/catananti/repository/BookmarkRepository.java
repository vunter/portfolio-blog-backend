package dev.catananti.repository;

import dev.catananti.entity.Bookmark;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface BookmarkRepository extends ReactiveCrudRepository<Bookmark, Long> {

    @Query("SELECT b.* FROM bookmarks b JOIN articles a ON b.article_id = a.id WHERE b.visitor_hash = :visitorHash ORDER BY b.created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Bookmark> findByVisitorHash(String visitorHash, int limit, int offset);

    @Query("SELECT COUNT(*) FROM bookmarks WHERE visitor_hash = :visitorHash")
    Mono<Long> countByVisitorHash(String visitorHash);

    Mono<Bookmark> findByArticleIdAndVisitorHash(Long articleId, String visitorHash);

    Mono<Void> deleteByArticleIdAndVisitorHash(Long articleId, String visitorHash);

    @Query("SELECT b.* FROM bookmarks b JOIN articles a ON b.article_id = a.id WHERE b.user_id = :userId ORDER BY b.created_at DESC LIMIT :limit OFFSET :offset")
    Flux<Bookmark> findByUserId(Long userId, int limit, int offset);

    @Query("SELECT COUNT(*) FROM bookmarks WHERE user_id = :userId")
    Mono<Long> countByUserId(Long userId);

    Mono<Bookmark> findByArticleIdAndUserId(Long articleId, Long userId);

    Mono<Void> deleteByArticleIdAndUserId(Long articleId, Long userId);
}
