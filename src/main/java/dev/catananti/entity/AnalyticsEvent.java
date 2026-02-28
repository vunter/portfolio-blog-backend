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

@Table("analytics_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsEvent implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Column("article_id")
    private Long articleId;

    @Column("event_type")
    private String eventType; // VIEW, LIKE, SHARE, CLICK, SCROLL_DEPTH

    @Column("user_ip")
    private String userIp;

    @Column("user_agent")
    private String userAgent;

    private String referrer;

    @Column("created_at")
    private LocalDateTime createdAt;

    // Additional metadata stored as JSON string
    private String metadata;
}
