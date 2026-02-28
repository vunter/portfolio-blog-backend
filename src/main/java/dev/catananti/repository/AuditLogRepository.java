package dev.catananti.repository;

import dev.catananti.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface AuditLogRepository extends ReactiveCrudRepository<AuditLog, Long> {

    Flux<AuditLog> findByPerformedByOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Flux<AuditLog> findByEntityTypeOrderByCreatedAtDesc(String entityType, Pageable pageable);

    Flux<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);

    @Query("SELECT * FROM audit_logs WHERE created_at >= :since ORDER BY created_at DESC LIMIT :limit")
    Flux<AuditLog> findRecentLogs(LocalDateTime since, int limit);

    @Query("SELECT * FROM audit_logs WHERE action = :action AND created_at >= :since ORDER BY created_at DESC")
    Flux<AuditLog> findByActionSince(String action, LocalDateTime since);

    @Query("DELETE FROM audit_logs WHERE created_at < :cutoff")
    Mono<Void> deleteByCreatedAtBefore(LocalDateTime cutoff);
}
