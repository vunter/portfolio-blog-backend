package dev.catananti.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("article_reviews")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleReview implements Persistable<Long>, NewRecordAware {
    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Override
    public void setNewRecord(boolean newRecord) {
        this.newRecord = newRecord;
    }

    @Column("article_id")
    private Long articleId;

    @Column("reviewer_id")
    private Long reviewerId;

    @Column("status")
    private String status; // APPROVED, CHANGES_REQUESTED

    @Column("feedback")
    private String feedback;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
