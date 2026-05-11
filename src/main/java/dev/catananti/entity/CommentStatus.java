package dev.catananti.entity;

/**
 * Status values for comments.
 * Used directly as entity field type — R2DBC handles enum ↔ String conversion.
 */
public enum CommentStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SPAM;

    /**
     * Parse a status string, returning the given default if null or invalid.
     */
    public static CommentStatus fromString(String status, CommentStatus defaultStatus) {
        if (status == null || status.isBlank()) return defaultStatus;
        try {
            return valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultStatus;
        }
    }
}
