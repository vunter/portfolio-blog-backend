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
 * Testimonials / recommendations displayed on the home page.
 */
@Table("resume_testimonials")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeTestimonial implements Persistable<Long>, NewRecordAware {

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

    @Column("author_name")
    private String authorName;

    @Column("author_role")
    private String authorRole;

    @Column("author_company")
    private String authorCompany;

    @Column("author_image_url")
    private String authorImageUrl;

    private String text;

    @Column("accent_color")
    private String accentColor;

    @Column("sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
