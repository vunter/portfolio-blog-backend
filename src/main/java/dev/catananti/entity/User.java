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

@Table("users")
@Getter
@Setter
@ToString(exclude = {"passwordHash"})
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements Persistable<Long>, NewRecordAware {

    // TODO F-259: Persist lockout state (accountLockedUntil, failedAttempts) in DB instead of in-memory LoginAttemptService
    // TODO F-260: Add emailVerified boolean field to enforce email verification on registration

    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }
    
    private String email;
    
    @Column("password_hash")
    private String passwordHash;
    
    private String name;

    private String username;

    @Column("avatar_url")
    private String avatarUrl;

    private String bio;
    
    @Builder.Default
    private String role = "VIEWER";

    @Builder.Default
    private Boolean active = true;

    @Column("cf_email_rule_id")
    private String cfEmailRuleId;
    
    @Column("created_at")
    private LocalDateTime createdAt;
    
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
