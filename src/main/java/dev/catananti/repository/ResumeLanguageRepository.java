package dev.catananti.repository;

import dev.catananti.entity.ResumeLanguage;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeLanguageRepository extends R2dbcRepository<ResumeLanguage, Long> {

    Flux<ResumeLanguage> findByProfileIdOrderBySortOrderAsc(Long profileId);

    Mono<Void> deleteByProfileId(Long profileId);
}
