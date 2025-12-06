package dev.catananti.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Tracks role upgrade requests from users (e.g. VIEWER → DEV).
 * An admin can later approve or reject the request.
 */
@Table("role_upgrade_requests")
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleUpgradeRequest implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("requested_role")
    private String requestedRole;

    @Column("reason")
    private String reason;

    /** PENDING, APPROVED, REJECTED */
    @Column("status")
    @Builder.Default
    private String status = "PENDING";

    @Column("reviewed_by")
    private Long reviewedBy;

    @Column("reviewed_at")
    private LocalDateTime reviewedAt;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }
}
