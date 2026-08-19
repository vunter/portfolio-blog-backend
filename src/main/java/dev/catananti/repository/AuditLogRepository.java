package dev.catananti.repository;

import dev.catananti.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface AuditLogRepository extends ReactiveCrudRepository<AuditLog, Long> {

    Flux<AuditLog> findByPerformedByOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // RQ-05: bounded in SQL — audit_logs grows without limit, so the cap cannot live in Java
    @Query("SELECT * FROM audit_logs WHERE entity_type = :entityType AND entity_id = :entityId ORDER BY created_at DESC LIMIT :limit")
    Flux<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId, int limit);

    @Query("SELECT * FROM audit_logs WHERE created_at >= :since ORDER BY created_at DESC LIMIT :limit")
    Flux<AuditLog> findRecentLogs(LocalDateTime since, int limit);

    // RQ-02/RQ-11: @Modifying + row count so callers can observe retention progress
    @Modifying
    @Query("DELETE FROM audit_logs WHERE created_at < :cutoff")
    Mono<Long> deleteByCreatedAtBefore(LocalDateTime cutoff);

    // AUD19-F140: newest occurrence of an action by a user (e.g. LOGIN → lastLogin on the
    // admin activity endpoint). ORDER BY + LIMIT 1 instead of MAX() so "no rows" maps to
    // an empty Mono rather than a NULL scalar. Served by idx_audit_logs_performed_by.
    @Query("SELECT created_at FROM audit_logs WHERE performed_by = :userId AND action = :action ORDER BY created_at DESC LIMIT 1")
    Mono<LocalDateTime> findLastActionAt(Long userId, String action);
}
