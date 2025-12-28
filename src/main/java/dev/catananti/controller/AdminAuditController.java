package dev.catananti.controller;

import dev.catananti.entity.AuditLog;
import dev.catananti.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin - Audit", description = "Audit log viewing endpoints")
@SecurityRequirement(name = "Bearer Authentication")
// TODO F-119: Add CSV/JSON export endpoint for audit logs
// TODO F-120: Implement audit log retention policy (e.g., auto-delete after 90 days)
@Slf4j
public class AdminAuditController {

    private final AuditService auditService;

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
}
