package dev.catananti.repository;

import dev.catananti.entity.SearchQuery;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

// Aggregate queries use DatabaseClient in AnalyticsService — R2DBC does not
// support interface-based projections with @Query for GROUP BY results.
@Repository
public interface SearchQueryRepository extends ReactiveCrudRepository<SearchQuery, Long> {
}
