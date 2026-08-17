package dev.catananti.repository;

import dev.catananti.entity.MfaBackupCode;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface MfaBackupCodeRepository extends R2dbcRepository<MfaBackupCode, Long> {

    Flux<MfaBackupCode> findByUserIdAndUsedFalse(Long userId);

    /**
     * SEC: atomic conditional consume — returns 1 only for the request that actually
     * flips used=false to true, so a backup code can never authenticate twice under
     * concurrency (same pattern as PasswordResetTokenRepository.markAsUsedConditionally).
     */
    @Modifying
    @Query("UPDATE mfa_backup_codes SET used = true, used_at = :usedAt WHERE id = :id AND used = false")
    Mono<Long> markUsedIfUnused(Long id, LocalDateTime usedAt);

    Mono<Long> countByUserIdAndUsedFalse(Long userId);

    Mono<Void> deleteByUserId(Long userId);
}
