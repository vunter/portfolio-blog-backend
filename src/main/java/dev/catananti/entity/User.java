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

    @Column("email_verified")
    @Builder.Default
    private Boolean emailVerified = false;

    @Column("mfa_enabled")
    @Builder.Default
    private Boolean mfaEnabled = false;

    @Column("mfa_preferred_method")
    @Builder.Default
    private String mfaPreferredMethod = "TOTP";
    
    // F-259: Persist lockout state in DB for distributed-safe brute-force protection
    @Column("account_locked_until")
    private LocalDateTime accountLockedUntil;

    @Column("failed_login_attempts")
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    @Column("created_at")
    private LocalDateTime createdAt;
    
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
