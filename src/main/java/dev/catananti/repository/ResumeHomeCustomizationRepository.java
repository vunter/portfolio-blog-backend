package dev.catananti.repository;

import dev.catananti.entity.ResumeHomeCustomization;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeHomeCustomizationRepository extends R2dbcRepository<ResumeHomeCustomization, Long> {

    Flux<ResumeHomeCustomization> findByProfileIdOrderBySortOrderAsc(Long profileId);

    Mono<Void> deleteByProfileId(Long profileId);
}
