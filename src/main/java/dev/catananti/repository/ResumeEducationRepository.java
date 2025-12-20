package dev.catananti.repository;

import dev.catananti.entity.ResumeEducation;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeEducationRepository extends R2dbcRepository<ResumeEducation, Long> {

    Flux<ResumeEducation> findByProfileIdOrderBySortOrderAsc(Long profileId);

    Mono<Void> deleteByProfileId(Long profileId);
}
