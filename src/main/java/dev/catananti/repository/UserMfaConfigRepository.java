package dev.catananti.repository;

import dev.catananti.entity.UserMfaConfig;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository for user MFA configuration.
 */
public interface UserMfaConfigRepository extends R2dbcRepository<UserMfaConfig, Long> {

    Mono<UserMfaConfig> findByUserIdAndMethod(Long userId, String method);

    Flux<UserMfaConfig> findByUserId(Long userId);

    Mono<Void> deleteByUserId(Long userId);

    Mono<Void> deleteByUserIdAndMethod(Long userId, String method);
}
