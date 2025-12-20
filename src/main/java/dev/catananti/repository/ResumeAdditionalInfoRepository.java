package dev.catananti.repository;

import dev.catananti.entity.ResumeAdditionalInfo;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeAdditionalInfoRepository extends R2dbcRepository<ResumeAdditionalInfo, Long> {

    Flux<ResumeAdditionalInfo> findByProfileIdOrderBySortOrderAsc(Long profileId);

    Mono<Void> deleteByProfileId(Long profileId);
}
