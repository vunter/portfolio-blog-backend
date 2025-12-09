package dev.catananti.repository;

import dev.catananti.entity.ArticleI18n;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

// TODO F-287: Standardize null vs empty string handling for i18n fields
@Repository
public class ArticleI18nRepository {

    private final DatabaseClient databaseClient;

    public ArticleI18nRepository(R2dbcEntityTemplate r2dbcTemplate) {
        this.databaseClient = r2dbcTemplate.getDatabaseClient();
    }

    public Mono<ArticleI18n> findByArticleIdAndLocale(Long articleId, String locale) {
        return databaseClient
                .sql("SELECT * FROM article_i18n WHERE article_id = :articleId AND locale = :locale")
                .bind("articleId", articleId)
                .bind("locale", locale)
                .map(this::mapRow)
                .one();
    }

    public Flux<ArticleI18n> findByArticleId(Long articleId) {
        return databaseClient
                .sql("SELECT * FROM article_i18n WHERE article_id = :articleId")
                .bind("articleId", articleId)
                .map(this::mapRow)
                .all();
    }

    public Flux<String> findLocalesByArticleId(Long articleId) {
        return databaseClient
                .sql("SELECT DISTINCT locale FROM article_i18n WHERE article_id = :articleId")
                .bind("articleId", articleId)
                .map(row -> row.get("locale", String.class))
                .all();
    }

    public Mono<Void> upsert(ArticleI18n i18n) {
        return databaseClient
                .sql("""
                        INSERT INTO article_i18n (article_id, locale, title, subtitle, content, excerpt,
                            seo_title, seo_description, seo_keywords, auto_translated, reviewed, translated_at)
                        VALUES (:articleId, :locale, :title, :subtitle, :content, :excerpt,
                            :seoTitle, :seoDescription, :seoKeywords, :autoTranslated, :reviewed, :translatedAt)
                        ON CONFLICT (article_id, locale)
                        DO UPDATE SET title = :title, subtitle = :subtitle,
                            content = :content, excerpt = :excerpt,
                            seo_title = :seoTitle, seo_description = :seoDescription,
                            seo_keywords = :seoKeywords, auto_translated = :autoTranslated,
                            reviewed = :reviewed, translated_at = :translatedAt
                        """)
                .bind("articleId", i18n.getArticleId())
                .bind("locale", i18n.getLocale())
                .bind("title", i18n.getTitle())
                .bind("subtitle", i18n.getSubtitle() != null ? i18n.getSubtitle() : "")
                .bind("content", i18n.getContent())
                .bind("excerpt", i18n.getExcerpt() != null ? i18n.getExcerpt() : "")
                .bind("seoTitle", i18n.getSeoTitle() != null ? i18n.getSeoTitle() : "")
                .bind("seoDescription", i18n.getSeoDescription() != null ? i18n.getSeoDescription() : "")
                .bind("seoKeywords", i18n.getSeoKeywords() != null ? i18n.getSeoKeywords() : "")
                .bind("autoTranslated", i18n.getAutoTranslated())
                .bind("reviewed", i18n.getReviewed())
                .bind("translatedAt", i18n.getTranslatedAt() != null ? i18n.getTranslatedAt() : LocalDateTime.now())
                .then();
    }

    public Mono<Void> deleteByArticleIdAndLocale(Long articleId, String locale) {
        return databaseClient
                .sql("DELETE FROM article_i18n WHERE article_id = :articleId AND locale = :locale")
                .bind("articleId", articleId)
                .bind("locale", locale)
                .then();
    }

    private ArticleI18n mapRow(io.r2dbc.spi.Readable row) {
        return ArticleI18n.builder()
                .articleId(row.get("article_id", Long.class))
                .locale(row.get("locale", String.class))
                .title(row.get("title", String.class))
                .subtitle(row.get("subtitle", String.class))
                .content(row.get("content", String.class))
                .excerpt(row.get("excerpt", String.class))
                .seoTitle(row.get("seo_title", String.class))
                .seoDescription(row.get("seo_description", String.class))
                .seoKeywords(row.get("seo_keywords", String.class))
                .autoTranslated(row.get("auto_translated", Boolean.class))
                .reviewed(row.get("reviewed", Boolean.class))
                .translatedAt(row.get("translated_at", LocalDateTime.class))
                .build();
    }
}
