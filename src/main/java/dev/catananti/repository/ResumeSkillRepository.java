package dev.catananti.repository;

import dev.catananti.entity.ResumeSkill;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeSkillRepository extends R2dbcRepository<ResumeSkill, Long> {

    Flux<ResumeSkill> findByProfileIdOrderBySortOrderAsc(Long profileId);

    Mono<Void> deleteByProfileId(Long profileId);
}
