package dev.catananti.service;

/**
 * Standardized notification event types for admin SSE notifications.
 * Used by {@link NotificationEventService} instead of hardcoded strings.
 */
public enum NotificationType {
    ARTICLE("article"),
    COMMENT("comment"),
    SUBSCRIBER("subscriber"),
    CONTACT("contact"),
    AUTH("auth");

    private final String value;

    NotificationType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
