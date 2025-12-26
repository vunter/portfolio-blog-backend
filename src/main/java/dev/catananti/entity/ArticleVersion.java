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

/**
 * Entity representing a historical version of an article.
 * Stores the content and metadata at a specific point in time.
 */
@Table("article_versions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleVersion implements Persistable<Long>, NewRecordAware {

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

    @Column("version_number")
    private Integer versionNumber;

    private String title;
    private String subtitle;
    private String content;
    private String excerpt;

    @Column("cover_image_url")
    private String coverImageUrl;

    @Column("change_summary")
    private String changeSummary;

    @Column("changed_by")
    private Long changedBy;

    @Column("changed_by_name")
    private String changedByName;

    @Column("created_at")
    private LocalDateTime createdAt;
}
