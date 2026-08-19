package dev.catananti.controller;

import dev.catananti.config.PaginationConfig;
import dev.catananti.entity.AuditLog;
import dev.catananti.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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

    @Spy
    private PaginationConfig paginationConfig = new PaginationConfig();

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
                        assertThat(log.action()).isEqualTo("CREATE");
                        assertThat(log.entityType()).isEqualTo("ARTICLE");
                    })
                    .assertNext(log -> {
                        assertThat(log.action()).isEqualTo("UPDATE");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should cap days at 90 and limit at 500")
        void shouldCapDaysAndLimit() {
            when(auditService.getRecentLogs(90, 500))
                    .thenReturn(Flux.just(auditLog1));

            // Controller caps: Math.min(days, 90), Math.min(limit, 500)
            // AUD19C-SNOW: id serialized as String
            StepVerifier.create(controller.getRecentLogs(200, 1000))
                    .assertNext(log -> assertThat(log.id()).isEqualTo("1"))
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

            // AUD19C-SNOW: performedBy serialized as String
            StepVerifier.create(controller.getLogsByUser(1L, 0, 20))
                    .assertNext(log -> assertThat(log.performedBy()).isEqualTo("1"))
                    .assertNext(log -> assertThat(log.performedBy()).isEqualTo("1"))
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
    @DisplayName("AUD18-JM10: sensitive-value redaction")
    class SanitizeDetails {

        private AuditLog logWithDetails(String details) {
            return AuditLog.builder()
                    .id(10L)
                    .action("UPDATE")
                    .entityType("USER")
                    .entityId("42")
                    .performedByEmail("admin@example.com")
                    .details(details)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        @Test
        @DisplayName("Should redact the VALUE of a JSON password field, keeping the key and other fields")
        void shouldRedactJsonPasswordValue() {
            when(auditService.getRecentLogs(7, 50))
                    .thenReturn(Flux.just(logWithDetails(
                            "{\"password\":\"hunter2\",\"name\":\"John Doe\"}")));

            StepVerifier.create(controller.getRecentLogs(7, 50))
                    .assertNext(log -> {
                        assertThat(log.details()).doesNotContain("hunter2");
                        assertThat(log.details()).contains("password");
                        assertThat(log.details()).contains("[REDACTED]");
                        assertThat(log.details()).contains("John Doe");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should redact key=value token pairs, keeping non-sensitive pairs")
        void shouldRedactKeyValueToken() {
            when(auditService.getRecentLogs(7, 50))
                    .thenReturn(Flux.just(logWithDetails(
                            "refreshToken=eyJhbGciOiJIUzI1NiJ9.abc.def ip=192.168.1.1 outcome=SUCCESS")));

            StepVerifier.create(controller.getRecentLogs(7, 50))
                    .assertNext(log -> {
                        assertThat(log.details()).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
                        assertThat(log.details()).contains("refreshToken=[REDACTED]");
                        assertThat(log.details()).contains("ip=192.168.1.1");
                        assertThat(log.details()).contains("outcome=SUCCESS");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should redact Authorization: Bearer header values")
        void shouldRedactBearerToken() {
            when(auditService.getRecentLogs(7, 50))
                    .thenReturn(Flux.just(logWithDetails(
                            "Request rejected. Authorization: Bearer eyJraWQiOiJr.eyJzdWIi.sig9")));

            StepVerifier.create(controller.getRecentLogs(7, 50))
                    .assertNext(log -> {
                        assertThat(log.details()).doesNotContain("eyJraWQiOiJr");
                        assertThat(log.details()).contains("[REDACTED]");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should redact quoted apiKey values in JSON payloads")
        void shouldRedactApiKeyValue() {
            when(auditService.getRecentLogs(7, 50))
                    .thenReturn(Flux.just(logWithDetails(
                            "{\"apiKey\": \"sk-live-123456\", \"client_secret\": \"shhh\"}")));

            StepVerifier.create(controller.getRecentLogs(7, 50))
                    .assertNext(log -> {
                        assertThat(log.details()).doesNotContain("sk-live-123456");
                        assertThat(log.details()).doesNotContain("shhh");
                        assertThat(log.details()).contains("apiKey");
                        assertThat(log.details()).contains("client_secret");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should NOT mangle free text that merely mentions a sensitive word")
        void shouldKeepFreeTextMentions() {
            when(auditService.getRecentLogs(7, 50))
                    .thenReturn(Flux.just(logWithDetails("User changed password via settings page")));

            StepVerifier.create(controller.getRecentLogs(7, 50))
                    .assertNext(log -> assertThat(log.details())
                            .isEqualTo("User changed password via settings page"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("AUD19C-SNOW: Snowflake ids serialized as Strings")
    class SnowflakeIdSerialization {

        @Test
        @DisplayName("Should serialize an id above 2^53 as the exact string, not a JSON number")
        void shouldSerializeBigIdAsExactString() throws Exception {
            long bigId = 348260000000000001L; // > Number.MAX_SAFE_INTEGER (2^53)
            AuditLog bigLog = AuditLog.builder()
                    .id(bigId)
                    .action("LOGIN_FAILED")
                    .entityType("USER")
                    .entityId("42")
                    .performedBy(null) // failed logins have no performedBy
                    .performedByEmail("intruder@example.com")
                    .details("Login failed")
                    .createdAt(LocalDateTime.now())
                    .build();

            when(auditService.getRecentLogs(7, 50)).thenReturn(Flux.just(bigLog));

            var responses = controller.getRecentLogs(7, 50).collectList().block();
            assertThat(responses).hasSize(1);
            var dto = responses.getFirst();
            assertThat(dto.id()).isEqualTo("348260000000000001");
            assertThat(dto.performedBy()).isNull(); // nullable-safe, no "null" string

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            String json = mapper.writeValueAsString(dto);
            assertThat(json).contains("\"id\":\"348260000000000001\"");
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/audit/entity/{entityType}/{entityId}")
    class GetLogsByEntity {

        @Test
        @DisplayName("Should return logs for specific entity")
        void shouldReturnLogsByEntity() {
            when(auditService.getLogsByEntity("ARTICLE", "1001", 500))
                    .thenReturn(Flux.just(auditLog1, auditLog2));

            StepVerifier.create(controller.getLogsByEntity("ARTICLE", "1001"))
                    .assertNext(log -> {
                        assertThat(log.entityType()).isEqualTo("ARTICLE");
                        assertThat(log.entityId()).isEqualTo("1001");
                    })
                    .assertNext(log -> assertThat(log.entityId()).isEqualTo("1001"))
                    .verifyComplete();
        }
    }
}
