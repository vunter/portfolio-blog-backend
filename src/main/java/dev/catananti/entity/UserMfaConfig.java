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
 * Entity representing a user's MFA (Multi-Factor Authentication) configuration.
 * Supports TOTP (authenticator apps) and EMAIL (email-based OTP).
 */
@Table("user_mfa_config")
@Getter
@Setter
@ToString(exclude = {"secretEncrypted"})
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMfaConfig implements Persistable<Long>, NewRecordAware {

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

    /**
     * MFA method: "TOTP" or "EMAIL"
     */
    @Builder.Default
    private String method = "TOTP";

    /**
     * AES-encrypted TOTP secret (Base32-encoded before encryption).
     * Null for EMAIL method (OTP is generated on-the-fly and stored in Redis).
     */
    @Column("secret_encrypted")
    private String secretEncrypted;

    /**
     * Whether the user has completed initial verification of this MFA method.
     */
    @Builder.Default
    private Boolean verified = false;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
