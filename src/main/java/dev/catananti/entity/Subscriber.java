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

    @Column("name")
    private String name;

    @Column("status")
    @Builder.Default
    private String status = "PENDING"; // PENDING, CONFIRMED, UNSUBSCRIBED

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

    public boolean isConfirmed() {
        return "CONFIRMED".equals(status);
    }

    public boolean isActive() {
        return "CONFIRMED".equals(status);
    }
}
