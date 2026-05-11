package dev.catananti.scheduler;

import dev.catananti.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogCleanupScheduler")
class AuditLogCleanupSchedulerTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditLogCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        SchedulerLock lock = new SchedulerLock(null);
        scheduler = new AuditLogCleanupScheduler(auditLogRepository, lock);
    }

    @Test
    @DisplayName("should delete audit logs older than retention period")
    void shouldDeleteOldAuditLogs() throws InterruptedException {
        when(auditLogRepository.deleteByCreatedAtBefore(any(LocalDateTime.class)))
                .thenReturn(Mono.empty());

        LocalDateTime before = LocalDateTime.now().minusDays(90).minusMinutes(1);

        scheduler.cleanupOldAuditLogs();
        Thread.sleep(200);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditLogRepository).deleteByCreatedAtBefore(captor.capture());
        assertThat(captor.getValue()).isAfter(before);
    }

    @Test
    @DisplayName("should handle repository errors gracefully")
    void shouldHandleErrors() throws InterruptedException {
        when(auditLogRepository.deleteByCreatedAtBefore(any(LocalDateTime.class)))
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        scheduler.cleanupOldAuditLogs();
        Thread.sleep(200);

        verify(auditLogRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }
}
