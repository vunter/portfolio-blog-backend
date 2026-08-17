package dev.catananti.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("subscribers")
public class Subscriber implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Column("email")
    private String email;

    public void setEmail(String email) {
        this.email = email != null ? email.strip().toLowerCase() : null;
    }

    @Column("name")
    private String name;

    @Column("status")
    @Builder.Default
    private SubscriberStatus status = SubscriberStatus.PENDING;

    @Column("confirmation_token")
    private String confirmationToken;

    @Column("unsubscribe_token")
    private String unsubscribeToken;

    @Column("confirmed_at")
    private LocalDateTime confirmedAt;

    @Column("unsubscribed_at")
    private LocalDateTime unsubscribedAt;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("analytics_consent")
    @Builder.Default
    private Boolean analyticsConsent = false;

    // Link to the user account. Only set when both sides proved the address
    // (users.email_verified AND status = CONFIRMED); written via conditional
    // UPDATEs in SubscriberRepository, never through save().
    @Column("user_id")
    private Long userId;

    @Column("linked_at")
    private LocalDateTime linkedAt;

    @Column("link_origin")
    private String linkOrigin;

    @Column("unlinked_at")
    private LocalDateTime unlinkedAt;

    // Who undid the link: only the holder's own refusal ('USER') blocks
    // automatic re-linking; 'ADMIN' and 'ACCOUNT_DELETED' do not.
    @Column("unlinked_by")
    private String unlinkedBy;

    public boolean isConfirmed() {
        return status == SubscriberStatus.CONFIRMED;
    }

    public boolean isActive() {
        return status == SubscriberStatus.CONFIRMED;
    }
}
