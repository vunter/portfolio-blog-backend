package dev.catananti.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Implementation of ArticleTagRepository using R2DBC DatabaseClient.
 * Centralises the raw SQL for article_tags join table operations,
 * previously scattered inline in ArticleService (CODE-03).
 */
@Repository
@RequiredArgsConstructor
public class ArticleTagRepositoryImpl implements ArticleTagRepository {

    private final R2dbcEntityTemplate r2dbcTemplate;

    private static final String FIND_TAGS_BY_ARTICLE_IDS =
            "SELECT article_id, tag_id FROM article_tags WHERE article_id = ANY(:ids)";

    private static final String INSERT_ARTICLE_TAG =
            "INSERT INTO article_tags (article_id, tag_id) VALUES (:articleId, :tagId)";

    private static final String DELETE_BY_ARTICLE_ID =
            "DELETE FROM article_tags WHERE article_id = :articleId";

    private static final String COMMENT_COUNT_BY_ARTICLE_IDS =
            "SELECT article_id, COUNT(*) as cnt FROM comments " +
            "WHERE article_id = ANY(:ids) AND status = 'APPROVED' GROUP BY article_id";

    @Override
    public Flux<long[]> findTagIdsByArticleIds(Long[] articleIds) {
        return r2dbcTemplate.getDatabaseClient()
                .sql(FIND_TAGS_BY_ARTICLE_IDS)
                .bind("ids", articleIds)
                .map((row, meta) -> new long[]{
                        row.get("article_id", Long.class),
                        row.get("tag_id", Long.class)
                })
                .all();
    }

    @Override
    public Mono<Void> insertArticleTag(Long articleId, Long tagId) {
        return r2dbcTemplate.getDatabaseClient()
                .sql(INSERT_ARTICLE_TAG)
                .bind("articleId", articleId)
                .bind("tagId", tagId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    @Override
    public Mono<Void> deleteByArticleId(Long articleId) {
        return r2dbcTemplate.getDatabaseClient()
                .sql(DELETE_BY_ARTICLE_ID)
                .bind("articleId", articleId)
                .fetch()
                .rowsUpdated()
                .then();
    }
}
