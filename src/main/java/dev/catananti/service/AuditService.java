package dev.catananti.service;

import dev.catananti.entity.AuditLog;
import dev.catananti.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Service for logging audit events for admin actions.
 * Provides persistent storage of who did what and when.
 * Event types are defined in {@link AuditEventType}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final IdService idService;

    /**
     * Log an admin action.
     */
    public Mono<Void> logAction(String action, String entityType, String entityId, 
                                 Long performedBy, String performedByEmail, String details) {
        return logAction(action, entityType, entityId, performedBy, performedByEmail, details, null);
    }

    /**
     * Log an admin action with IP address.
     */
    public Mono<Void> logAction(String action, String entityType, String entityId,
                                 Long performedBy, String performedByEmail, 
                                 String details, String ipAddress) {
        AuditLog auditLog = AuditLog.builder()
                .id(idService.nextId())
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .performedBy(performedBy)
                .performedByEmail(performedByEmail)
                .details(details)
                .ipAddress(ipAddress)
                .createdAt(LocalDateTime.now())
                .build();

        return auditLogRepository.save(auditLog)
                .doOnSuccess(saved -> log.info("Audit: {} - {} {} by {} ({})", 
                        action, entityType, entityId, performedByEmail, details))
                .doOnError(e -> log.error("Failed to save audit log: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty()) // Don't fail the main operation
                .then();
    }

    // Common action shortcuts
    public Mono<Void> logUserCreated(Long userId, String email, Long createdBy, String createdByEmail) {
        return logAction(AuditEventType.USER_CREATE.action(), "USER", userId.toString(), createdBy, createdByEmail, 
                "Created user: " + email);
    }

    public Mono<Void> logUserDeleted(Long userId, String email, Long deletedBy, String deletedByEmail) {
        return logAction(AuditEventType.USER_DELETE.action(), "USER", userId.toString(), deletedBy, deletedByEmail, 
                "Deleted user: " + email);
    }

    public Mono<Void> logRoleChanged(Long userId, String email, String oldRole, String newRole, 
                                      Long changedBy, String changedByEmail) {
        return logAction(AuditEventType.USER_UPDATE_ROLE.action(), "USER", userId.toString(), changedBy, changedByEmail,
                "Changed role from %s to %s for user: %s".formatted(oldRole, newRole, email));
    }

    public Mono<Void> logArticleCreated(Long articleId, String slug, Long createdBy, String createdByEmail) {
        return logAction(AuditEventType.ARTICLE_CREATE.action(), "ARTICLE", articleId.toString(), createdBy, createdByEmail,
                "Created article: " + slug);
    }

    public Mono<Void> logArticleDeleted(Long articleId, String slug, Long deletedBy, String deletedByEmail) {
        return logAction(AuditEventType.ARTICLE_DELETE.action(), "ARTICLE", articleId.toString(), deletedBy, deletedByEmail,
                "Deleted article: " + slug);
    }

    public Mono<Void> logArticleRestored(Long articleId, String slug, int version, 
                                          Long restoredBy, String restoredByEmail) {
        return logAction(AuditEventType.ARTICLE_RESTORE.action(), "ARTICLE", articleId.toString(), restoredBy, restoredByEmail,
                "Restored article %s to version %d".formatted(slug, version));
    }

    public Mono<Void> logCacheInvalidated(String cacheType, Long clearedBy, String clearedByEmail) {
        return logAction(AuditEventType.CACHE_INVALIDATE.action(), "CACHE", cacheType, clearedBy, clearedByEmail,
                "Invalidated cache: " + cacheType);
    }

    public Mono<Void> logDataExported(String exportType, Long exportedBy, String exportedByEmail) {
        return logAction(AuditEventType.DATA_EXPORT.action(), "DATA", exportType, exportedBy, exportedByEmail,
                "Exported data: " + exportType);
    }

    public Mono<Void> logDataImported(int articlesImported, int tagsImported, 
                                       Long importedBy, String importedByEmail) {
        return logAction(AuditEventType.DATA_IMPORT.action(), "DATA", "blog_data", importedBy, importedByEmail,
                "Imported %d articles and %d tags".formatted(articlesImported, tagsImported));
    }

    public Mono<Void> logLoginSuccess(Long userId, String email, String ipAddress) {
        return logAction(AuditEventType.LOGIN.action(), "USER", userId.toString(), userId, email, 
                "Successful login", ipAddress);
    }

    public Mono<Void> logLoginFailed(String email, String reason, String ipAddress) {
        return logAction(AuditEventType.LOGIN_FAILED.action(), "USER", email, null, email, 
                "Failed login: " + reason, ipAddress);
    }

    public Mono<Void> logAccountLocked(String email, int failedAttempts, String ipAddress) {
        return logAction(AuditEventType.ACCOUNT_LOCKED.action(), "USER", email, null, email,
                "Account locked after " + failedAttempts + " failed attempts", ipAddress);
    }

    public Mono<Void> logPasswordReset(Long userId, String email) {
        return logAction(AuditEventType.PASSWORD_RESET.action(), "USER", userId.toString(), userId, email,
                "Password reset completed");
    }

    public Mono<Void> logEmailChange(Long userId, String oldEmail, String newEmail) {
        return logAction(AuditEventType.EMAIL_CHANGE.action(), "USER", userId.toString(), userId, oldEmail,
                "Email changed from " + oldEmail + " to " + newEmail);
    }

    public Mono<Void> logPasswordResetRequested(String email, String ipAddress) {
        return logAction(AuditEventType.PASSWORD_RESET_REQUESTED.action(), "USER", email, null, email,
                "Password reset requested", ipAddress);
    }

    // Query methods
    public Flux<AuditLog> getRecentLogs(int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return auditLogRepository.findRecentLogs(since, limit);
    }

    public Flux<AuditLog> getLogsByUser(Long userId, int page, int size) {
        return auditLogRepository.findByPerformedByOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
    }

    public Flux<AuditLog> getLogsByEntity(String entityType, String entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
    }
}
