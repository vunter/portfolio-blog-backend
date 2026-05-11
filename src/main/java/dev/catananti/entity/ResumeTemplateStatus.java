package dev.catananti.entity;

/**
 * Status values for resume templates.
 * Used directly as entity field type — R2DBC handles enum ↔ String conversion.
 */
public enum ResumeTemplateStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED;

    /**
     * Parse a status string, returning the given default if null or invalid.
     */
    public static ResumeTemplateStatus fromString(String status, ResumeTemplateStatus defaultStatus) {
        if (status == null || status.isBlank()) return defaultStatus;
        try {
            return valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultStatus;
        }
    }
}
