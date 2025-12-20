package dev.catananti.repository;

import dev.catananti.entity.ResumeCertification;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeCertificationRepository extends R2dbcRepository<ResumeCertification, Long> {

    Flux<ResumeCertification> findByProfileIdOrderBySortOrderAsc(Long profileId);

    Mono<Void> deleteByProfileId(Long profileId);
}
