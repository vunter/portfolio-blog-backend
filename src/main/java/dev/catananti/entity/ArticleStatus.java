package dev.catananti.entity;

/**
 * Status values for articles.
 * Used directly as entity field type — R2DBC handles enum ↔ String conversion.
 */
public enum ArticleStatus {
    DRAFT,
    PUBLISHED,
    SCHEDULED,
    REVIEW,
    ARCHIVED;

    /**
     * Parse a status string, returning the given default if null or invalid.
     */
    public static ArticleStatus fromString(String status, ArticleStatus defaultStatus) {
        if (status == null || status.isBlank()) return defaultStatus;
        try {
            return valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultStatus;
        }
    }
}
