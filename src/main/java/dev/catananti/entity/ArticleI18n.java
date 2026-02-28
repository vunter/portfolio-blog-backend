package dev.catananti.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

// F-246: Unique constraint on (article_id, locale) is enforced by PRIMARY KEY in schema.sql:
// CREATE TABLE article_i18n (... PRIMARY KEY (article_id, locale));
// The upsert in ArticleI18nRepository uses ON CONFLICT (article_id, locale) accordingly.
@Table("article_i18n")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleI18n {

    @Id
    @Column("article_id")
    private Long articleId;

    private String locale;

    private String title;
    private String subtitle;
    private String content;
    private String excerpt;

    @Column("seo_title")
    private String seoTitle;

    @Column("seo_description")
    private String seoDescription;

    @Column("seo_keywords")
    private String seoKeywords;

    @Column("auto_translated")
    @Builder.Default
    private Boolean autoTranslated = false;

    @Builder.Default
    private Boolean reviewed = false;

    @Column("translated_at")
    private LocalDateTime translatedAt;
}
