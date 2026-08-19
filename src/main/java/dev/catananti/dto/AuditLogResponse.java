package dev.catananti.dto;

import dev.catananti.entity.AuditLog;

import java.time.LocalDateTime;

/**
 * AUD19C-SNOW: API-facing view of {@link AuditLog}. Snowflake Longs exceed
 * JS Number.MAX_SAFE_INTEGER (2^53), so {@code id} and {@code performedBy}
 * are serialized as Strings like the other migrated DTOs.
 */
public record AuditLogResponse(
        String id,
        String action,
        String entityType,
        String entityId,
        String performedBy,
        String performedByEmail,
        String details,
        String ipAddress,
        LocalDateTime createdAt
) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                String.valueOf(log.getId()),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                // performedBy is null for failed logins — keep it null, not "null"
                log.getPerformedBy() != null ? String.valueOf(log.getPerformedBy()) : null,
                log.getPerformedByEmail(),
                log.getDetails(),
                log.getIpAddress(),
                log.getCreatedAt()
        );
    }
}
