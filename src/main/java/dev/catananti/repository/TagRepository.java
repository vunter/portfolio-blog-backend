package dev.catananti.repository;

import dev.catananti.entity.Tag;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;

@Repository
public interface TagRepository extends ReactiveCrudRepository<Tag, Long> {

    Mono<Tag> findBySlug(String slug);

    @Query("SELECT * FROM tags WHERE slug IN (:slugs)")
    Flux<Tag> findBySlugIn(Set<String> slugs);

    Mono<Boolean> existsBySlug(String slug);

    @Query("SELECT t.* FROM tags t " +
           "JOIN article_tags at ON t.id = at.tag_id " +
           "WHERE at.article_id = :articleId")
    Flux<Tag> findByArticleId(Long articleId);

    @Query("SELECT COUNT(*) FROM article_tags at " +
           "JOIN articles a ON a.id = at.article_id " +
           "WHERE at.tag_id = :tagId AND a.status = 'PUBLISHED'")
    Mono<Long> countPublishedArticlesByTagId(Long tagId);

    // Author-scoped tag queries for DEV/EDITOR dashboard
    @Query("SELECT DISTINCT t.* FROM tags t " +
           "JOIN article_tags at ON t.id = at.tag_id " +
           "JOIN articles a ON a.id = at.article_id " +
           "WHERE a.author_id = :authorId")
    Flux<Tag> findByAuthorId(Long authorId);

    @Query("SELECT COUNT(DISTINCT t.id) FROM tags t " +
           "JOIN article_tags at ON t.id = at.tag_id " +
           "JOIN articles a ON a.id = at.article_id " +
           "WHERE a.author_id = :authorId")
    Mono<Long> countByAuthorId(Long authorId);
}
