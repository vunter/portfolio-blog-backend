package dev.catananti.service;

import dev.catananti.entity.AuditLog;
import dev.catananti.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private IdService idService;

    @InjectMocks
    private AuditService auditService;

    private AuditLog testAuditLog;

    @BeforeEach
    void setUp() {
        testAuditLog = AuditLog.builder()
                .id(100L)
                .action("CREATE")
                .entityType("ARTICLE")
                .entityId("42")
                .performedBy(1L)
                .performedByEmail("admin@example.com")
                .details("Created article: test-slug")
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ==================== logAction ====================

    @Nested
    @DisplayName("logAction")
    class LogAction {

        @Test
        @DisplayName("Should save audit log with all fields")
        void shouldSaveAuditLogWithAllFields() {
            // Given
            when(idService.nextId()).thenReturn(100L);
            when(auditLogRepository.save(any(AuditLog.class)))
                    .thenReturn(Mono.just(testAuditLog));

            // When & Then
            StepVerifier.create(auditService.logAction(
                    "CREATE", "ARTICLE", "42", 1L, "admin@example.com", "details", "127.0.0.1"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            AuditLog saved = captor.getValue();
            assertThat(saved.getAction()).isEqualTo("CREATE");
            assertThat(saved.getEntityType()).isEqualTo("ARTICLE");
            assertThat(saved.getIpAddress()).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("Should save audit log without IP address (overload)")
        void shouldSaveAuditLogWithoutIpAddress() {
            // Given
            when(idService.nextId()).thenReturn(101L);
            when(auditLogRepository.save(any(AuditLog.class)))
                    .thenReturn(Mono.just(testAuditLog));

            // When & Then
            StepVerifier.create(auditService.logAction(
                    "DELETE", "USER", "5", 2L, "admin@example.com", "Deleted user"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getIpAddress()).isNull();
        }

        @Test
        @DisplayName("Should be error-resilient on save failure")
        void shouldBeErrorResilientOnSaveFailure() {
            // Given
            when(idService.nextId()).thenReturn(102L);
            when(auditLogRepository.save(any(AuditLog.class)))
                    .thenReturn(Mono.error(new RuntimeException("DB down")));

            // When & Then — should complete, not error
            StepVerifier.create(auditService.logAction(
                    "CREATE", "USER", "1", 1L, "admin@example.com", "test", null))
                    .verifyComplete();
        }
    }

    // ==================== Shortcut methods ====================

    @Nested
    @DisplayName("Shortcut methods")
    class ShortcutMethods {

        @BeforeEach
        void setUpMocks() {
            when(idService.nextId()).thenReturn(200L);
            when(auditLogRepository.save(any(AuditLog.class)))
                    .thenReturn(Mono.just(testAuditLog));
        }

        @Test
        @DisplayName("logUserCreated should log CREATE USER action")
        void logUserCreated() {
            StepVerifier.create(auditService.logUserCreated(10L, "user@example.com", 1L, "admin@example.com"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("CREATE");
            assertThat(captor.getValue().getEntityType()).isEqualTo("USER");
            assertThat(captor.getValue().getDetails()).contains("user@example.com");
        }

        @Test
        @DisplayName("logRoleChanged should log UPDATE_ROLE action with role details")
        void logRoleChanged() {
            StepVerifier.create(auditService.logRoleChanged(
                    10L, "user@example.com", "EDITOR", "ADMIN", 1L, "admin@example.com"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("UPDATE_ROLE");
            assertThat(captor.getValue().getDetails()).contains("EDITOR").contains("ADMIN");
        }

        @Test
        @DisplayName("logArticleCreated should log CREATE ARTICLE action")
        void logArticleCreated() {
            StepVerifier.create(auditService.logArticleCreated(42L, "my-article", 1L, "admin@example.com"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("CREATE");
            assertThat(captor.getValue().getEntityType()).isEqualTo("ARTICLE");
            assertThat(captor.getValue().getDetails()).contains("my-article");
        }

        @Test
        @DisplayName("logLoginSuccess should log LOGIN action with IP address")
        void logLoginSuccess() {
            StepVerifier.create(auditService.logLoginSuccess(5L, "user@example.com", "192.168.1.1"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("LOGIN");
            assertThat(captor.getValue().getIpAddress()).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("logCacheInvalidated should log INVALIDATE_CACHE action")
        void logCacheInvalidated() {
            StepVerifier.create(auditService.logCacheInvalidated("articles", 1L, "admin@example.com"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("INVALIDATE_CACHE");
            assertThat(captor.getValue().getEntityType()).isEqualTo("CACHE");
            assertThat(captor.getValue().getDetails()).contains("articles");
        }

        @Test
        @DisplayName("logUserDeleted should log DELETE USER action")
        void logUserDeleted() {
            StepVerifier.create(auditService.logUserDeleted(10L, "user@example.com", 1L, "admin@example.com"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("DELETE");
            assertThat(captor.getValue().getEntityType()).isEqualTo("USER");
        }

        @Test
        @DisplayName("logLoginFailed should log LOGIN_FAILED action")
        void logLoginFailed() {
            StepVerifier.create(auditService.logLoginFailed("hacker@bad.com", "invalid password", "10.0.0.1"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("LOGIN_FAILED");
            assertThat(captor.getValue().getIpAddress()).isEqualTo("10.0.0.1");
        }
    }

    // ==================== Query methods ====================

    @Nested
    @DisplayName("Query methods")
    class QueryMethods {

        @Test
        @DisplayName("getRecentLogs should return logs within day range")
        void getRecentLogs() {
            // Given
            when(auditLogRepository.findRecentLogs(any(LocalDateTime.class), eq(50)))
                    .thenReturn(Flux.just(testAuditLog));

            // When & Then
            StepVerifier.create(auditService.getRecentLogs(7, 50))
                    .assertNext(log -> {
                        assertThat(log.getAction()).isEqualTo("CREATE");
                        assertThat(log.getEntityType()).isEqualTo("ARTICLE");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("getRecentLogs should return empty when no logs")
        void getRecentLogsShouldReturnEmpty() {
            // Given
            when(auditLogRepository.findRecentLogs(any(LocalDateTime.class), eq(10)))
                    .thenReturn(Flux.empty());

            // When & Then
            StepVerifier.create(auditService.getRecentLogs(1, 10))
                    .verifyComplete();
        }

        @Test
        @DisplayName("getLogsByUser should return logs for specific user")
        void getLogsByUser() {
            // Given
            when(auditLogRepository.findByPerformedByOrderByCreatedAtDesc(eq(1L), any(PageRequest.class)))
                    .thenReturn(Flux.just(testAuditLog));

            // When & Then
            StepVerifier.create(auditService.getLogsByUser(1L, 0, 20))
                    .assertNext(log -> {
                        assertThat(log.getPerformedBy()).isEqualTo(1L);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("getLogsByEntity should return logs for specific entity")
        void getLogsByEntity() {
            // Given
            when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("ARTICLE", "42"))
                    .thenReturn(Flux.just(testAuditLog));

            // When & Then
            StepVerifier.create(auditService.getLogsByEntity("ARTICLE", "42"))
                    .assertNext(log -> {
                        assertThat(log.getEntityType()).isEqualTo("ARTICLE");
                        assertThat(log.getEntityId()).isEqualTo("42");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("getLogsByEntity should return empty for unknown entity")
        void getLogsByEntityShouldReturnEmptyForUnknown() {
            // Given
            when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("ARTICLE", "999"))
                    .thenReturn(Flux.empty());

            // When & Then
            StepVerifier.create(auditService.getLogsByEntity("ARTICLE", "999"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("getLogsByUser should paginate correctly")
        void getLogsByUserShouldPaginate() {
            // Given
            AuditLog log1 = AuditLog.builder().id(1L).action("CREATE").entityType("ARTICLE").performedBy(1L).createdAt(LocalDateTime.now()).build();
            AuditLog log2 = AuditLog.builder().id(2L).action("DELETE").entityType("ARTICLE").performedBy(1L).createdAt(LocalDateTime.now().minusHours(1)).build();

            when(auditLogRepository.findByPerformedByOrderByCreatedAtDesc(eq(1L), eq(PageRequest.of(0, 2))))
                    .thenReturn(Flux.just(log1, log2));

            // When & Then
            StepVerifier.create(auditService.getLogsByUser(1L, 0, 2).collectList())
                    .assertNext(logs -> {
                        assertThat(logs).hasSize(2);
                        assertThat(logs.get(0).getAction()).isEqualTo("CREATE");
                        assertThat(logs.get(1).getAction()).isEqualTo("DELETE");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("getLogsByUser should return empty for second page with no results")
        void getLogsByUserShouldReturnEmptyForEmptyPage() {
            when(auditLogRepository.findByPerformedByOrderByCreatedAtDesc(eq(1L), eq(PageRequest.of(5, 10))))
                    .thenReturn(Flux.empty());

            StepVerifier.create(auditService.getLogsByUser(1L, 5, 10))
                    .verifyComplete();
        }

        @Test
        @DisplayName("getLogsByEntity should return multiple logs for same entity")
        void getLogsByEntityShouldReturnMultipleLogs() {
            AuditLog create = AuditLog.builder().id(1L).action("CREATE").entityType("ARTICLE").entityId("42").createdAt(LocalDateTime.now()).build();
            AuditLog update = AuditLog.builder().id(2L).action("UPDATE").entityType("ARTICLE").entityId("42").createdAt(LocalDateTime.now().minusMinutes(30)).build();
            AuditLog delete = AuditLog.builder().id(3L).action("DELETE").entityType("ARTICLE").entityId("42").createdAt(LocalDateTime.now().minusHours(1)).build();

            when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("ARTICLE", "42"))
                    .thenReturn(Flux.just(create, update, delete));

            StepVerifier.create(auditService.getLogsByEntity("ARTICLE", "42").collectList())
                    .assertNext(logs -> {
                        assertThat(logs).hasSize(3);
                        assertThat(logs).extracting(AuditLog::getAction)
                                .containsExactly("CREATE", "UPDATE", "DELETE");
                    })
                    .verifyComplete();
        }
    }

    // ==================== Additional Shortcut method coverage ====================

    @Nested
    @DisplayName("Additional Shortcut methods")
    class AdditionalShortcutMethods {

        @BeforeEach
        void setUpMocks() {
            when(idService.nextId()).thenReturn(300L);
            when(auditLogRepository.save(any(AuditLog.class)))
                    .thenReturn(Mono.just(testAuditLog));
        }

        @Test
        @DisplayName("logArticleDeleted should log DELETE ARTICLE action")
        void logArticleDeleted() {
            StepVerifier.create(auditService.logArticleDeleted(42L, "my-article", 1L, "admin@example.com"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("DELETE");
            assertThat(captor.getValue().getEntityType()).isEqualTo("ARTICLE");
            assertThat(captor.getValue().getDetails()).contains("my-article");
        }

        @Test
        @DisplayName("logArticleRestored should log RESTORE action with version")
        void logArticleRestored() {
            StepVerifier.create(auditService.logArticleRestored(42L, "my-article", 3, 1L, "admin@example.com"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("RESTORE");
            assertThat(captor.getValue().getDetails()).contains("version 3");
        }

        @Test
        @DisplayName("logDataExported should log EXPORT action")
        void logDataExported() {
            StepVerifier.create(auditService.logDataExported("articles", 1L, "admin@example.com"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("EXPORT");
            assertThat(captor.getValue().getEntityType()).isEqualTo("DATA");
        }

        @Test
        @DisplayName("logDataImported should log IMPORT action with counts")
        void logDataImported() {
            StepVerifier.create(auditService.logDataImported(15, 8, 1L, "admin@example.com"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("IMPORT");
            assertThat(captor.getValue().getDetails()).contains("15 articles");
            assertThat(captor.getValue().getDetails()).contains("8 tags");
        }

        @Test
        @DisplayName("logAccountLocked should log ACCOUNT_LOCKED with failed attempts")
        void logAccountLocked() {
            StepVerifier.create(auditService.logAccountLocked("hacker@bad.com", 5, "10.0.0.1"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("ACCOUNT_LOCKED");
            assertThat(captor.getValue().getDetails()).contains("5 failed attempts");
            assertThat(captor.getValue().getIpAddress()).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("logPasswordReset should log PASSWORD_RESET action")
        void logPasswordReset() {
            StepVerifier.create(auditService.logPasswordReset(5L, "user@example.com"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("PASSWORD_RESET");
            assertThat(captor.getValue().getEntityType()).isEqualTo("USER");
        }

        @Test
        @DisplayName("logPasswordResetRequested should log PASSWORD_RESET_REQUESTED action")
        void logPasswordResetRequested() {
            StepVerifier.create(auditService.logPasswordResetRequested("user@example.com", "192.168.1.1"))
                    .verifyComplete();

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("PASSWORD_RESET_REQUESTED");
            assertThat(captor.getValue().getIpAddress()).isEqualTo("192.168.1.1");
        }
    }
}
