package dev.catananti.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("mfa_backup_codes")
@Getter
@Setter
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaBackupCode implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Column("user_id")
    private Long userId;

    @Column("code_hash")
    private String codeHash;

    @Builder.Default
    private Boolean used = false;

    @Column("used_at")
    private LocalDateTime usedAt;

    @Column("created_at")
    private LocalDateTime createdAt;
}
