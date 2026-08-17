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
import java.util.ArrayList;
import java.util.List;

@Table("comments")
@Getter
@Setter
@ToString(exclude = {"replies"})
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Comment implements Persistable<Long>, NewRecordAware {

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

    @Column("author_name")
    private String authorName;

    @Column("author_email")
    private String authorEmail;

    private String content;

    @Builder.Default
    private CommentStatus status = CommentStatus.PENDING;

    @Column("parent_id")
    private Long parentId;

    /**
     * Structural authorship (V21). Survives erasure on purpose: author_name and
     * author_email are anonymized while user_id keeps referential integrity
     * without re-identifying the person.
     */
    @Column("user_id")
    private Long userId;

    @Column("moderation_note")
    private String moderationNote;

    @Column("likes_count")
    @Builder.Default
    private Integer likesCount = 0;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Transient
    @Builder.Default
    private List<Comment> replies = new ArrayList<>();
}
