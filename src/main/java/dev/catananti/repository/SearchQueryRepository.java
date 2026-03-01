package dev.catananti.repository;

import dev.catananti.entity.SearchQuery;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

// BUG-12: Aggregate queries use DatabaseClient in the service layer
// (R2DBC does not support interface-based projections with @Query)
@Repository
public interface SearchQueryRepository extends ReactiveCrudRepository<SearchQuery, Long> {
}
