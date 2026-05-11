package dev.catananti.entity;

/**
 * Status values for article reviews.
 * Used directly as entity field type — R2DBC handles enum ↔ String conversion.
 */
public enum ArticleReviewStatus {
    APPROVED,
    CHANGES_REQUESTED
}
