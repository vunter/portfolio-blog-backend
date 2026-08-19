package dev.catananti.dto;

import java.time.LocalDateTime;

/**
 * AUD19-F140: activity summary for the admin user list (GET /api/v1/admin/users/{id}/activity).
 *
 * <p>Field names mirror the frontend's {@code UserActivity} interface exactly
 * (user-list.component.ts) — camelCase, with {@code lastLogin} and
 * {@code accountCreated} optional (serialized as {@code null} when unknown).</p>
 *
 * @param lastLogin       most recent login, derived from existing data (newest LOGIN audit
 *                        entry or refresh-token issuance); {@code null} when neither source
 *                        has a record — e.g. the user never logged in, or their tokens
 *                        expired and were purged before any LOGIN audit rows were written
 * @param accountCreated  users.created_at
 * @param articlesCreated number of articles authored by the user
 * @param commentsPosted  number of comments linked to the user via comments.user_id (V21)
 */
public record UserActivityResponse(
        LocalDateTime lastLogin,
        LocalDateTime accountCreated,
        long articlesCreated,
        long commentsPosted) {
}
