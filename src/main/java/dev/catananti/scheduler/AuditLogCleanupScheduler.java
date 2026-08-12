package dev.catananti.scheduler;

import dev.catananti.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogCleanupScheduler {

    private final AuditLogRepository auditLogRepository;
    private final SchedulerLock schedulerLock;

    @Value("${app.audit.retention-days:90}")
    private int retentionDays = 90;

    public reactor.core.publisher.Mono<Void> cleanupOldAuditLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        return schedulerLock.executeWithLock("audit-cleanup", Duration.ofMinutes(10),
                auditLogRepository.deleteByCreatedAtBefore(cutoff)
                        .doOnSuccess(count -> log.info("Audit log retention cleanup completed: {} rows deleted", count))
                        .doOnError(e -> log.error("Failed to cleanup old audit logs: {}", e.getMessage(), e))
                        .onErrorComplete()
                        .then()
        );
    }

    /**
     * Daily audit-log retention sweep.
     */
    @Scheduled(cron = "${app.audit.cleanup-cron:0 0 2 * * *}")
    public void cleanupOldAuditLogsScheduled() {
        cleanupOldAuditLogs().subscribe();
    }
}
