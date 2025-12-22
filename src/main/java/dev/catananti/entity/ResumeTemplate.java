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
 * Entity representing a resume HTML template.
 * Templates can be stored, versioned, and converted to PDF.
 */
@Table("resume_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeTemplate implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    /**
     * Flag to indicate if this is a new entity (for R2DBC insert vs update).
     * When built via Builder, defaults to true (INSERT).
     * When loaded from DB via R2DBC, defaults to false (UPDATE).
     */
    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    /**
     * Unique slug for URL-friendly access.
     */
    private String slug;

    /**
     * Custom public alias for URL-friendly download (e.g., "leonardo-catananti").
     * Used as the public URL path: /api/public/resume/{alias}/pdf
     */
    @Column("url_alias")
    private String alias;

    /**
     * Template name/title for identification.
     * Stored as JSONB: {"en": "My Resume", "pt-br": "Meu Currículo"}
     */
    private LocalizedText name;

    /**
     * Description of the template.
     * Stored as JSONB: {"en": "Professional resume", "pt-br": "Currículo profissional"}
     */
    private LocalizedText description;

    /**
     * The full HTML content of the resume template.
     */
    @Column("html_content")
    private String htmlContent;

    /**
     * The CSS styles for the resume template.
     */
    @Column("css_content")
    private String cssContent;

    /**
     * Template status: DRAFT, ACTIVE, ARCHIVED
     */
    @Builder.Default
    private String status = "DRAFT";

    /**
     * Owner of this template.
     */
    @Column("owner_id")
    private Long ownerId;

    /**
     * Version number for tracking changes.
     */
    @Builder.Default
    private Integer version = 1;

    /**
     * Whether this is the default template for the owner.
     */
    @Column("is_default")
    @Builder.Default
    private Boolean isDefault = false;

    /**
     * Paper size: A4, LETTER, LEGAL
     */
    @Column("paper_size")
    @Builder.Default
    private String paperSize = "A4";

    /**
     * Orientation: PORTRAIT, LANDSCAPE
     */
    @Builder.Default
    private String orientation = "PORTRAIT";

    /**
     * Number of times this template was used to generate PDFs.
     */
    @Column("download_count")
    @Builder.Default
    private Integer downloadCount = 0;

    /**
     * Preview thumbnail URL (optional).
     */
    @Column("preview_url")
    private String previewUrl;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    /**
     * Increment version and update timestamp.
     */
    public void incrementVersion() {
        this.version = (this.version == null ? 1 : this.version) + 1;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Increment download count.
     */
    public void incrementDownloadCount() {
        this.downloadCount = (this.downloadCount == null ? 0 : this.downloadCount) + 1;
    }
}
