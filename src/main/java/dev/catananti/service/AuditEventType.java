package dev.catananti.service;

/**
 * Standardized audit event types for structured logging.
 * Used by {@link AuditService} instead of free-form action strings.
 */
public enum AuditEventType {
    // Authentication events
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    ACCOUNT_LOCKED,
    PASSWORD_RESET,
    PASSWORD_RESET_REQUESTED,

    // User management
    USER_CREATE,
    USER_DELETE,
    USER_UPDATE_ROLE,
    USER_ACTIVATE,
    USER_DEACTIVATE,

    // Article management
    ARTICLE_CREATE,
    ARTICLE_UPDATE,
    ARTICLE_DELETE,
    ARTICLE_PUBLISH,
    ARTICLE_RESTORE,

    // System operations
    CACHE_INVALIDATE,
    DATA_EXPORT,
    DATA_IMPORT,
    SETTINGS_UPDATE,
    EMAIL_CHANGE;

    /**
     * Returns the action string used in audit log storage.
     */
    public String action() {
        return name();
    }
}
