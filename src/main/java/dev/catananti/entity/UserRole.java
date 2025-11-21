package dev.catananti.entity;

/**
 * Role values for users.
 * Entity fields remain as String for R2DBC compatibility.
 * Use these constants instead of hardcoded strings for type-safe comparisons.
 */
public enum UserRole {
    ADMIN,
    DEV,
    EDITOR,
    VIEWER;

    /**
     * Check if the given role string matches this enum value.
     */
    public boolean matches(String role) {
        return this.name().equals(role);
    }
}
