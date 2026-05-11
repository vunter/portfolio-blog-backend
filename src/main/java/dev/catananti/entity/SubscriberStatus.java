package dev.catananti.entity;

/**
 * Status values for newsletter subscribers.
 * Used directly as entity field type — R2DBC handles enum ↔ String conversion.
 */
public enum SubscriberStatus {
    PENDING,
    CONFIRMED,
    UNSUBSCRIBED
}
