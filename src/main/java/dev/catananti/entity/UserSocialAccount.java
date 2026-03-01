package dev.catananti.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("user_social_accounts")
@Getter
@Setter
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSocialAccount implements Persistable<Long>, NewRecordAware {

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

    /** OAuth2 provider: "google" or "github" */
    private String provider;

    /** Unique ID from the provider */
    @Column("provider_id")
    private String providerId;

    @Column("provider_email")
    private String providerEmail;

    @Column("display_name")
    private String displayName;

    @Column("avatar_url")
    private String avatarUrl;

    @Column("linked_at")
    private LocalDateTime linkedAt;
}
