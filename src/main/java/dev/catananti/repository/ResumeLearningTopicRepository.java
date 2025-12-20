package dev.catananti.repository;

import dev.catananti.entity.ResumeLearningTopic;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeLearningTopicRepository extends R2dbcRepository<ResumeLearningTopic, Long> {

    Flux<ResumeLearningTopic> findByProfileIdOrderBySortOrderAsc(Long profileId);

    Mono<Void> deleteByProfileId(Long profileId);
}
