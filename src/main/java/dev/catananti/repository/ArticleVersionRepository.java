package dev.catananti.repository;

import dev.catananti.entity.ArticleVersion;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ArticleVersionRepository extends ReactiveCrudRepository<ArticleVersion, Long> {

    Flux<ArticleVersion> findByArticleIdOrderByVersionNumberDesc(Long articleId);

    @Query("SELECT * FROM article_versions WHERE article_id = :articleId ORDER BY version_number DESC LIMIT 1")
    Mono<ArticleVersion> findLatestByArticleId(Long articleId);

    @Query("SELECT COALESCE(MAX(version_number), 0) FROM article_versions WHERE article_id = :articleId")
    Mono<Integer> findMaxVersionNumber(Long articleId);

    Mono<ArticleVersion> findByArticleIdAndVersionNumber(Long articleId, Integer versionNumber);

    @Query("SELECT COUNT(*) FROM article_versions WHERE article_id = :articleId")
    Mono<Long> countByArticleId(Long articleId);

    Mono<Void> deleteByArticleId(Long articleId);
}
