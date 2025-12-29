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

@Table("media_assets")
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAsset implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Column("original_filename")
    private String originalFilename;

    @Column("stored_filename")
    private String storedFilename;

    @Column("storage_key")
    private String storageKey;

    @Column("content_type")
    private String contentType;

    @Column("file_size")
    private Long fileSize;

    /** AVATAR, BLOG_COVER, BLOG_CONTENT, COMMENT, PROJECT, TESTIMONIAL, GENERAL */
    private String purpose;

    @Column("alt_text")
    private String altText;

    /** The public-facing URL for this asset */
    private String url;

    @Column("uploader_id")
    private Long uploaderId;

    @Column("created_at")
    private LocalDateTime createdAt;
}
