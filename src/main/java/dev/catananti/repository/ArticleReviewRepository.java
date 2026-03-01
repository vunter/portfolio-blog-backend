package dev.catananti.repository;

import dev.catananti.entity.ArticleReview;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface ArticleReviewRepository extends ReactiveCrudRepository<ArticleReview, Long> {
    @Query("SELECT * FROM article_reviews WHERE article_id = :articleId ORDER BY created_at DESC")
    Flux<ArticleReview> findByArticleIdOrderByCreatedAtDesc(Long articleId);
}
