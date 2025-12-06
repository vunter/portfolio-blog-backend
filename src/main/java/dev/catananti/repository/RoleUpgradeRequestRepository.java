package dev.catananti.repository;

import dev.catananti.entity.RoleUpgradeRequest;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
}
