package dev.catananti.repository;

import dev.catananti.entity.RoleUpgradeRequest;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface RoleUpgradeRequestRepository extends ReactiveCrudRepository<RoleUpgradeRequest, Long> {

    @Query("SELECT * FROM role_upgrade_requests WHERE user_id = :userId ORDER BY created_at DESC LIMIT 1")
    Mono<RoleUpgradeRequest> findLatestByUserId(Long userId);

    @Query("SELECT * FROM role_upgrade_requests WHERE user_id = :userId AND status = 'PENDING' LIMIT 1")
    Mono<RoleUpgradeRequest> findPendingByUserId(Long userId);

    @Query("SELECT * FROM role_upgrade_requests WHERE status = 'PENDING' ORDER BY created_at ASC")
    Flux<RoleUpgradeRequest> findAllPending();

    @Query("SELECT * FROM role_upgrade_requests ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<RoleUpgradeRequest> findAllPaged(int limit, long offset);

    @Query("SELECT COUNT(*) FROM role_upgrade_requests")
    Mono<Long> countAll();

    @Query("SELECT COUNT(*) FROM role_upgrade_requests WHERE status = 'PENDING'")
    Mono<Long> countPending();

    /**
     * CC-08: atomic PENDING → APPROVED/REJECTED transition. Returns 1 only for the
     * admin whose review actually claimed the request; a concurrent review of the
     * same request sees 0 rows and must not proceed.
     */
    @Modifying
    @Query("UPDATE role_upgrade_requests SET status = :newStatus, reviewed_by = :reviewedBy, reviewed_at = :reviewedAt WHERE id = :id AND status = 'PENDING'")
    Mono<Long> transitionFromPending(Long id, String newStatus, Long reviewedBy, LocalDateTime reviewedAt);
}
