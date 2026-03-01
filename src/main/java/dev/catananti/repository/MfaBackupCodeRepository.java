package dev.catananti.repository;

import dev.catananti.entity.MfaBackupCode;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface MfaBackupCodeRepository extends R2dbcRepository<MfaBackupCode, Long> {

    Flux<MfaBackupCode> findByUserIdAndUsedFalse(Long userId);

    Mono<Long> countByUserIdAndUsedFalse(Long userId);

    Mono<Void> deleteByUserId(Long userId);
}
