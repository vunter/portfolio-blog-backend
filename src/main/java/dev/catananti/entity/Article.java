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
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

// F-243: ArticleSummary record extracted to dev.catananti.dto.ArticleSummary
@Table("articles")
@Getter
@Setter
@ToString(exclude = {"content", "tags", "replies"})
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Article implements Persistable<Long>, NewRecordAware {

    @Id
    private Long id;

    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @jakarta.validation.constraints.NotBlank(message = "Article slug must not be blank")
    private String slug;
    private String title;
    private String subtitle;
    private String content;
    private String excerpt;
    
    @Column("cover_image_url")
    private String coverImageUrl;
    
    @Column("author_id")
    private Long authorId;
    
    @Transient
    private String authorName;

    @Transient
    private String authorEmail;

    @Transient
    private String authorRole;

    @Transient
    private String authorAvatarUrl;
    
    @Builder.Default
    private String status = "DRAFT";
    
    @Column("published_at")
    private LocalDateTime publishedAt;
    
    @Column("reading_time_minutes")
    private Integer readingTimeMinutes;
    
    @Column("views_count")
    @Builder.Default
    private Integer viewsCount = 0;
    
    @Column("likes_count")
    @Builder.Default
    private Integer likesCount = 0;
    
    @Column("seo_title")
    private String seoTitle;
    
    @Column("seo_description")
    private String seoDescription;
    
    @Column("seo_keywords")
    private String seoKeywords;
    
    @Column("scheduled_at")
    private LocalDateTime scheduledAt;

    @Column("original_locale")
    @Builder.Default
    private String originalLocale = "en";

    @Transient
    @Builder.Default
    private Set<Tag> tags = new HashSet<>();

    @Transient
    @Builder.Default
    private Integer commentCount = 0;
    
    @Column("created_at")
    private LocalDateTime createdAt;
    
    @Column("updated_at")
    private LocalDateTime updatedAt;

    public void incrementViews() {
        this.viewsCount = (this.viewsCount == null ? 0 : this.viewsCount) + 1;
    }

    public void incrementLikes() {
        this.likesCount = (this.likesCount == null ? 0 : this.likesCount) + 1;
    }
    
    public boolean isScheduled() {
        return "SCHEDULED".equals(this.status) && this.scheduledAt != null;
    }
    
    public boolean shouldPublishNow() {
        return isScheduled() && !LocalDateTime.now(ZoneOffset.UTC).isBefore(this.scheduledAt);
    }
}
