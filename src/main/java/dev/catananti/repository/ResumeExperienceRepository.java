package dev.catananti.repository;

import dev.catananti.entity.ResumeExperience;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeExperienceRepository extends R2dbcRepository<ResumeExperience, Long> {

    Flux<ResumeExperience> findByProfileIdOrderBySortOrderAsc(Long profileId);

    Mono<Void> deleteByProfileId(Long profileId);
}
