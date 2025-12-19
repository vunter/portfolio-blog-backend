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
 * Portfolio projects displayed on the home page.
 * Tech tags are stored as a JSON array string (e.g., ["Angular","Spring Boot"]).
 */
@Table("resume_projects")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeProject implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Column("profile_id")
    private Long profileId;

    private String title;

    private String description;

    @Column("image_url")
    private String imageUrl;

    @Column("project_url")
    private String projectUrl;

    @Column("repo_url")
    private String repoUrl;

    /** JSON array string, e.g. ["Angular","Spring Boot","PostgreSQL"] */
    @Column("tech_tags")
    private String techTags;

    private Boolean featured;

    @Column("sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
