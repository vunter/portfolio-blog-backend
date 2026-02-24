package dev.catananti.repository;

import dev.catananti.entity.ResumeProficiency;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeProficiencyRepository extends R2dbcRepository<ResumeProficiency, Long> {

    Flux<ResumeProficiency> findByProfileIdOrderBySortOrderAsc(Long profileId);

    Mono<Void> deleteByProfileId(Long profileId);
}
