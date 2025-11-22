package dev.catananti.entity;

/**
 * Status values for comments.
 * Entity fields remain as String for R2DBC compatibility.
 * Use these constants instead of hardcoded strings for type-safe comparisons.
 */
public enum CommentStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SPAM;

    /**
     * Check if the given status string matches this enum value.
     */
    public boolean matches(String status) {
        return this.name().equals(status);
    }
}
