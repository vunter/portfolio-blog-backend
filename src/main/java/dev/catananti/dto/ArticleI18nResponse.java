package dev.catananti.dto;

import dev.catananti.entity.ArticleI18n;

import java.time.LocalDateTime;

/**
 * DTO for article internationalization data.
 * Prevents direct entity exposure and controls which fields are visible in the API.
 */
public record ArticleI18nResponse(
        // AUD19C-SNOW: String, not Long — Snowflake ids exceed JS Number.MAX_SAFE_INTEGER (2^53)
        String articleId,
        String locale,
        String title,
        String subtitle,
        String content,
        String excerpt,
        String seoTitle,
        String seoDescription,
        String seoKeywords,
        Boolean autoTranslated,
        Boolean reviewed,
        LocalDateTime translatedAt
) {

    public static ArticleI18nResponse from(ArticleI18n entity) {
        return new ArticleI18nResponse(
                String.valueOf(entity.getArticleId()),
                entity.getLocale(),
                entity.getTitle(),
                entity.getSubtitle(),
                entity.getContent(),
                entity.getExcerpt(),
                entity.getSeoTitle(),
                entity.getSeoDescription(),
                entity.getSeoKeywords(),
                entity.getAutoTranslated(),
                entity.getReviewed(),
                entity.getTranslatedAt()
        );
    }
}
