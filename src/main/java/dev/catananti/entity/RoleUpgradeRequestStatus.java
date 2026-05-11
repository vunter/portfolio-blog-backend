package dev.catananti.entity;

/**
 * Status values for role upgrade requests.
 * Used directly as entity field type — R2DBC handles enum ↔ String conversion.
 */
public enum RoleUpgradeRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}
