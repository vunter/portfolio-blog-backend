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
 * Root entity for a user's resume profile.
 * Contains personal info, summary, and interests.
 */
@Table("resume_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeProfile implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Column("owner_id")
    private Long ownerId;

    @Builder.Default
    private String locale = "en";

    @Column("full_name")
    private String fullName;

    private String title;

    private String email;

    private String phone;

    private String linkedin;

    private String github;

    private String website;

    private String location;

    @Column("professional_summary")
    private String professionalSummary;

    private String interests;

    @Column("work_mode")
    private String workMode;

    private String timezone;

    @Column("employment_type")
    private String employmentType;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
