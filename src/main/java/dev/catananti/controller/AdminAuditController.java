package dev.catananti.controller;

import dev.catananti.entity.AuditLog;
import dev.catananti.repository.AuditLogRepository;
import dev.catananti.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin - Audit", description = "Audit log viewing endpoints")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminAuditController {

    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository;

    @Value("${app.audit.retention-days:90}")
    private int retentionDays = 90;

    // F-116: Pattern to detect sensitive data in audit log details
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(password|token|secret|credential|authorization|cookie|jwt|bearer)",
            Pattern.CASE_INSENSITIVE);

    /**
     * F-116: Mask sensitive data in audit log details field.
     * Prevents accidental exposure of passwords, tokens, etc. in raw audit entries.
     */
    private AuditLog sanitizeAuditLog(AuditLog log) {
        if (log.getDetails() != null && SENSITIVE_PATTERN.matcher(log.getDetails()).find()) {
            log.setDetails(SENSITIVE_PATTERN.matcher(log.getDetails())
                    .replaceAll("[REDACTED]"));
        }
        // Ensure IP address is included but no other PII leaks
        return log;
    }

    @GetMapping("/recent")
    @Operation(summary = "Get recent audit logs", description = "Retrieve the most recent audit log entries")
    public Flux<AuditLog> getRecentLogs(
            @Parameter(description = "Number of days to look back") @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days,
            @Parameter(description = "Maximum number of entries") @RequestParam(defaultValue = "50") @Min(1) @Max(1000) int limit) {
        log.debug("Fetching recent audit logs: days={}, limit={}", days, limit);
        return auditService.getRecentLogs(Math.min(days, 90), Math.min(limit, 500))
                .map(this::sanitizeAuditLog);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get audit logs by user", description = "Retrieve audit logs for a specific user")
    public Flux<AuditLog> getLogsByUser(
            @PathVariable Long userId,
            @Parameter(description = "Page number (0-indexed)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        log.debug("Fetching audit logs for userId={}, page={}, size={}", userId, page, size);
        return auditService.getLogsByUser(userId, page, Math.min(size, 100))
                .map(this::sanitizeAuditLog);
    }

    @GetMapping("/entity/{entityType}/{entityId}")
    @Operation(summary = "Get audit logs by entity", description = "Retrieve audit logs for a specific entity")
    public Flux<AuditLog> getLogsByEntity(
            @PathVariable @jakarta.validation.constraints.Pattern(regexp = "^[A-Z_]+$", message = "Invalid entity type format") String entityType,
            @PathVariable String entityId) {
        log.debug("Fetching audit logs for entityType={}, entityId={}", entityType, entityId);
        return auditService.getLogsByEntity(entityType, entityId)
                .take(500)
                .map(this::sanitizeAuditLog);
    }

    @GetMapping("/export/csv")
    @Operation(summary = "Export audit logs as CSV", description = "Export recent audit logs in CSV format")
    public Mono<ResponseEntity<String>> exportCsv(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return auditLogRepository.findRecentLogs(since, 10000)
                .map(this::sanitizeAuditLog)
                .map(entry -> String.join(",",
                        csvEscape(String.valueOf(entry.getId())),
                        csvEscape(entry.getAction()),
                        csvEscape(entry.getEntityType()),
                        csvEscape(entry.getEntityId()),
                        csvEscape(entry.getPerformedByEmail()),
                        csvEscape(entry.getDetails()),
                        csvEscape(entry.getIpAddress()),
                        csvEscape(String.valueOf(entry.getCreatedAt()))))
                .collectList()
                .map(lines -> {
                    StringBuilder csv = new StringBuilder("id,action,entity_type,entity_id,performed_by_email,details,ip_address,created_at\n");
                    lines.forEach(line -> csv.append(line).append("\n"));
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("text/csv"))
                            .header("Content-Disposition", "attachment; filename=\"audit-logs.csv\"")
                            .body(csv.toString());
                });
    }

    @GetMapping(value = "/export/json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Export audit logs as JSON", description = "Export recent audit logs in JSON format")
    public Flux<AuditLog> exportJson(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return auditLogRepository.findRecentLogs(since, 10000)
                .map(this::sanitizeAuditLog);
    }

    /**
     * F-120: Scheduled cleanup of audit logs older than retention threshold.
     * Runs daily at 2 AM.
     */
    @Scheduled(cron = "${app.audit.cleanup-cron:0 0 2 * * *}")
    public void cleanupOldAuditLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        auditLogRepository.deleteByCreatedAtBefore(cutoff)
                .subscribe(
                        result -> log.info("Audit log retention cleanup completed"),
                        error -> log.error("Failed to cleanup old audit logs: {}", error.getMessage())
                );
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
