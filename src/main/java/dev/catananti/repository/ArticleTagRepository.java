package dev.catananti.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository for article-tag many-to-many relationship operations.
 * Extracts raw SQL from ArticleService (CODE-03).
 * Since R2DBC doesn't support join entities directly, we use DatabaseClient
 * via a custom repository implementation.
 */
@Repository
public interface ArticleTagRepository {

    Flux<long[]> findTagIdsByArticleIds(Long[] articleIds);

    Mono<Void> insertArticleTag(Long articleId, Long tagId);

    Mono<Void> deleteByArticleId(Long articleId);
}
