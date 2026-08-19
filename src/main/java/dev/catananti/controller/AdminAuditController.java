package dev.catananti.controller;

import dev.catananti.config.PaginationConfig;
import dev.catananti.dto.AuditLogResponse;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final PaginationConfig paginationConfig;

    // AUD18-JM10: F-116 redacted the keyword itself ("password" → "[REDACTED]") and left
    // the secret VALUE sitting right next to it. This pattern binds a sensitive key
    // (password/token/secret/key/credential/authorization/cookie/jwt/bearer, with any
    // prefix/suffix, quoted or not) to the value that follows a ':' or '=' — JSON
    // ("password":"hunter2"), key=value (refreshToken=abc.def) and header
    // (Authorization: Bearer xyz) shapes — and redacts only the value, keeping the key
    // visible so the entry stays auditable.
    private static final Pattern SENSITIVE_KV_PATTERN = Pattern.compile(
            "([\"']?[\\w.-]*(?:password|passwd|token|secret|credential|authorization|cookie|jwt|bearer|key)[\\w.-]*[\"']?\\s*[=:]\\s*)"
                    + "(?:\"[^\"]*\"|'[^']*'|(?:Bearer\\s+)?[^\\s,;&}\\])]+)",
            Pattern.CASE_INSENSITIVE);

    // AUD18-JM10: standalone bearer tokens outside a key:value shape ("... Bearer eyJhb...")
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "\\b(Bearer\\s+)[A-Za-z0-9._~+/=-]+",
            Pattern.CASE_INSENSITIVE);

    /**
     * F-116/AUD18-JM10: Mask sensitive data in audit log details field.
     * Prevents accidental exposure of passwords, tokens, etc. in raw audit entries.
     */
    private AuditLog sanitizeAuditLog(AuditLog log) {
        if (log.getDetails() != null) {
            String sanitized = SENSITIVE_KV_PATTERN.matcher(log.getDetails())
                    .replaceAll("$1[REDACTED]");
            sanitized = BEARER_TOKEN_PATTERN.matcher(sanitized)
                    .replaceAll("$1[REDACTED]");
            log.setDetails(sanitized);
        }
        // Ensure IP address is included but no other PII leaks
        return log;
    }

    @GetMapping("/recent")
    @Operation(summary = "Get recent audit logs", description = "Retrieve the most recent audit log entries")
    public Flux<AuditLogResponse> getRecentLogs(
            @Parameter(description = "Number of days to look back") @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days,
            @Parameter(description = "Maximum number of entries") @RequestParam(defaultValue = "50") @Min(1) int limit) {
        log.debug("Fetching recent audit logs: days={}, limit={}", days, limit);
        // AUD19C-SNOW: map to AuditLogResponse (String ids) after the AUD18-JM10 sanitizer
        return auditService.getRecentLogs(Math.min(days, 90), Math.min(limit, 500))
                .map(this::sanitizeAuditLog)
                .map(AuditLogResponse::from);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get audit logs by user", description = "Retrieve audit logs for a specific user")
    public Flux<AuditLogResponse> getLogsByUser(
            @PathVariable Long userId,
            @Parameter(description = "Page number (0-indexed)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        size = paginationConfig.clampPageSize(size);
        log.debug("Fetching audit logs for userId={}, page={}, size={}", userId, page, size);
        return auditService.getLogsByUser(userId, page, Math.min(size, 100))
                .map(this::sanitizeAuditLog)
                .map(AuditLogResponse::from);
    }

    @GetMapping("/entity/{entityType}/{entityId}")
    @Operation(summary = "Get audit logs by entity", description = "Retrieve audit logs for a specific entity")
    public Flux<AuditLogResponse> getLogsByEntity(
            @PathVariable @jakarta.validation.constraints.Pattern(regexp = "^[A-Z_]+$", message = "Invalid entity type format") String entityType,
            @PathVariable String entityId) {
        log.debug("Fetching audit logs for entityType={}, entityId={}", entityType, entityId);
        // RQ-05: the 500-row cap is applied in SQL (LIMIT), not after transferring rows
        return auditService.getLogsByEntity(entityType, entityId, 500)
                .map(this::sanitizeAuditLog)
                .map(AuditLogResponse::from);
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
    public Flux<AuditLogResponse> exportJson(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return auditLogRepository.findRecentLogs(since, 10000)
                .map(this::sanitizeAuditLog)
                .map(AuditLogResponse::from);
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
