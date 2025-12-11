package dev.catananti.repository;

import dev.catananti.entity.AnalyticsEvent;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface AnalyticsRepository extends ReactiveCrudRepository<AnalyticsEvent, Long> {

    @Query("SELECT * FROM analytics_events WHERE article_id = :articleId ORDER BY created_at DESC LIMIT :limit")
    Flux<AnalyticsEvent> findByArticleId(Long articleId, int limit);

    @Query("SELECT COUNT(*) FROM analytics_events WHERE article_id = :articleId AND event_type = :eventType")
    Mono<Long> countByArticleIdAndEventType(Long articleId, String eventType);

    @Query("SELECT COUNT(*) FROM analytics_events WHERE event_type = :eventType AND created_at >= :since")
    Mono<Long> countByEventTypeSince(String eventType, LocalDateTime since);

    // Author-scoped analytics queries (for DEV/EDITOR dashboard)
    @Query("SELECT COUNT(*) FROM analytics_events ae " +
           "JOIN articles a ON ae.article_id = a.id " +
           "WHERE a.author_id = :authorId AND ae.event_type = :eventType AND ae.created_at >= :since")
    Mono<Long> countByAuthorIdAndEventTypeSince(Long authorId, String eventType, LocalDateTime since);

    // BUG-12: Removed Flux<Object[]> methods — R2DBC does not support Object[] projections.
    // Aggregate queries are now handled via DatabaseClient in AnalyticsService.

    Mono<Void> deleteByArticleId(Long articleId);
}
