package dev.catananti.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("article_reviews")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleReview {
    @Id
    private Long id;

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
}
