package dev.catananti.dto;

import dev.catananti.entity.RoleUpgradeRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for role upgrade requests.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleUpgradeRequestResponse {

    // AUD19C-4: Snowflake ids are serialized as strings (JS Number loses precision
    // past 2^53; the frontend already types them as string).
    private String id;
    private String userId;
    private String userName;
    private String userEmail;
    private String currentRole;
    private String requestedRole;
    private String reason;
    private String status;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;

    private static String asString(Long value) {
        return value != null ? String.valueOf(value) : null;
    }

    public static RoleUpgradeRequestResponse fromEntity(RoleUpgradeRequest entity) {
        return RoleUpgradeRequestResponse.builder()
                .id(asString(entity.getId()))
                .userId(asString(entity.getUserId()))
                .requestedRole(entity.getRequestedRole())
                .reason(entity.getReason())
                .status(entity.getStatus().name())
                .reviewedBy(asString(entity.getReviewedBy()))
                .reviewedAt(entity.getReviewedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * Enriched builder that includes user info (name, email, current role).
     */
    public static RoleUpgradeRequestResponse fromEntityWithUser(
            RoleUpgradeRequest entity, String userName, String userEmail, String currentRole) {
        return RoleUpgradeRequestResponse.builder()
                .id(asString(entity.getId()))
                .userId(asString(entity.getUserId()))
                .userName(userName)
                .userEmail(userEmail)
                .currentRole(currentRole)
                .requestedRole(entity.getRequestedRole())
                .reason(entity.getReason())
                .status(entity.getStatus().name())
                .reviewedBy(asString(entity.getReviewedBy()))
                .reviewedAt(entity.getReviewedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
