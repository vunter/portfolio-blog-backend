package dev.catananti.controller;

import dev.catananti.entity.AuditLog;
import dev.catananti.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAuditControllerTest {

    @Mock
    private AuditService auditService;

    @InjectMocks
    private AdminAuditController controller;

    private AuditLog auditLog1;
    private AuditLog auditLog2;

    @BeforeEach
    void setUp() {
        auditLog1 = AuditLog.builder()
                .id(1L)
                .action("CREATE")
                .entityType("ARTICLE")
                .entityId("1001")
                .performedBy(1L)
                .performedByEmail("admin@example.com")
                .details("Created article: Spring Boot Guide")
                .createdAt(LocalDateTime.now().minusHours(2))
                .build();

        auditLog2 = AuditLog.builder()
                .id(2L)
                .action("UPDATE")
                .entityType("ARTICLE")
                .entityId("1001")
                .performedBy(1L)
                .performedByEmail("admin@example.com")
                .details("Updated article: Spring Boot Guide")
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/audit/recent")
    class GetRecentLogs {

        @Test
        @DisplayName("Should return recent logs with default params")
        void shouldReturnRecentLogsWithDefaults() {
            when(auditService.getRecentLogs(7, 50))
                    .thenReturn(Flux.just(auditLog1, auditLog2));

            StepVerifier.create(controller.getRecentLogs(7, 50))
                    .assertNext(log -> {
                        assertThat(log.getAction()).isEqualTo("CREATE");
                        assertThat(log.getEntityType()).isEqualTo("ARTICLE");
                    })
                    .assertNext(log -> {
                        assertThat(log.getAction()).isEqualTo("UPDATE");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should cap days at 90 and limit at 500")
        void shouldCapDaysAndLimit() {
            when(auditService.getRecentLogs(90, 500))
                    .thenReturn(Flux.just(auditLog1));

            // Controller caps: Math.min(days, 90), Math.min(limit, 500)
            StepVerifier.create(controller.getRecentLogs(200, 1000))
                    .assertNext(log -> assertThat(log.getId()).isEqualTo(1L))
                    .verifyComplete();

            verify(auditService).getRecentLogs(90, 500);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/audit/user/{userId}")
    class GetLogsByUser {

        @Test
        @DisplayName("Should return logs for specific user")
        void shouldReturnLogsByUser() {
            when(auditService.getLogsByUser(1L, 0, 20))
                    .thenReturn(Flux.just(auditLog1, auditLog2));

            StepVerifier.create(controller.getLogsByUser(1L, 0, 20))
                    .assertNext(log -> assertThat(log.getPerformedBy()).isEqualTo(1L))
                    .assertNext(log -> assertThat(log.getPerformedBy()).isEqualTo(1L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should cap page size at 100")
        void shouldCapPageSize() {
            when(auditService.getLogsByUser(1L, 0, 100))
                    .thenReturn(Flux.just(auditLog1));

            StepVerifier.create(controller.getLogsByUser(1L, 0, 500))
                    .assertNext(log -> assertThat(log).isNotNull())
                    .verifyComplete();

            verify(auditService).getLogsByUser(1L, 0, 100);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/audit/entity/{entityType}/{entityId}")
    class GetLogsByEntity {

        @Test
        @DisplayName("Should return logs for specific entity")
        void shouldReturnLogsByEntity() {
            when(auditService.getLogsByEntity("ARTICLE", "1001"))
                    .thenReturn(Flux.just(auditLog1, auditLog2));

            StepVerifier.create(controller.getLogsByEntity("ARTICLE", "1001"))
                    .assertNext(log -> {
                        assertThat(log.getEntityType()).isEqualTo("ARTICLE");
                        assertThat(log.getEntityId()).isEqualTo("1001");
                    })
                    .assertNext(log -> assertThat(log.getEntityId()).isEqualTo("1001"))
                    .verifyComplete();
        }
    }
}
