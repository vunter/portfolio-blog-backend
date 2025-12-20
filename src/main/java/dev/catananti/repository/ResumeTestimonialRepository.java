package dev.catananti.repository;

import dev.catananti.entity.ResumeTestimonial;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeTestimonialRepository extends R2dbcRepository<ResumeTestimonial, Long> {

    Flux<ResumeTestimonial> findByProfileIdOrderBySortOrderAsc(Long profileId);

    Mono<Void> deleteByProfileId(Long profileId);
}
