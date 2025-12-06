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

    private Long id;
    private Long userId;
    private String userName;
    private String userEmail;
    private String currentRole;
    private String requestedRole;
    private String reason;
    private String status;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;

    public static RoleUpgradeRequestResponse fromEntity(RoleUpgradeRequest entity) {
        return RoleUpgradeRequestResponse.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .requestedRole(entity.getRequestedRole())
                .reason(entity.getReason())
                .status(entity.getStatus())
                .reviewedBy(entity.getReviewedBy())
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
                .id(entity.getId())
                .userId(entity.getUserId())
                .userName(userName)
                .userEmail(userEmail)
                .currentRole(currentRole)
                .requestedRole(entity.getRequestedRole())
                .reason(entity.getReason())
                .status(entity.getStatus())
                .reviewedBy(entity.getReviewedBy())
                .reviewedAt(entity.getReviewedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
