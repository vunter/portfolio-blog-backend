package dev.catananti.repository;

import dev.catananti.entity.ResumeProfile;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ResumeProfileRepository extends R2dbcRepository<ResumeProfile, Long> {

    Mono<ResumeProfile> findByOwnerIdAndLocale(Long ownerId, String locale);

    Flux<ResumeProfile> findByOwnerId(Long ownerId);

    /**
     * Find profiles whose locale starts with the given prefix (e.g., "pt" matches "pt-br", "pt-pt").
     */
    @Query("SELECT * FROM resume_profiles WHERE owner_id = :ownerId AND LOWER(locale) LIKE :prefix || '%' LIMIT 1")
    Mono<ResumeProfile> findByOwnerIdAndLocalePrefix(Long ownerId, String prefix);
}
