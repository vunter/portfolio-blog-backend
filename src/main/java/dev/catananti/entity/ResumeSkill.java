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

@Table("resume_skills")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeSkill implements Persistable<Long>, NewRecordAware {

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

    /**
     * Category label e.g. "Languages & Runtimes", "Backend & Frameworks"
     */
    private String category;

    /**
     * Comma-separated skill items, may contain HTML bold tags.
     */
    private String content;

    @Column("sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
