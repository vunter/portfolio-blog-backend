package dev.catananti.repository;

import dev.catananti.entity.SearchQuery;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

// Aggregate queries use DatabaseClient in AnalyticsService — R2DBC does not
// support interface-based projections with @Query for GROUP BY results.
@Repository
public interface SearchQueryRepository extends ReactiveCrudRepository<SearchQuery, Long> {

    /**
     * RQ-03/SI-5: batched retention delete — search_queries grows on every public
     * search and previously had no purge at all. Same 10k-batch shape as analytics.
     */
    @org.springframework.data.r2dbc.repository.Modifying
    @org.springframework.data.r2dbc.repository.Query("DELETE FROM search_queries WHERE id IN (SELECT id FROM search_queries WHERE created_at < :cutoff LIMIT 10000)")
    reactor.core.publisher.Mono<Long> deleteByCreatedAtBefore(java.time.LocalDateTime cutoff);
}
