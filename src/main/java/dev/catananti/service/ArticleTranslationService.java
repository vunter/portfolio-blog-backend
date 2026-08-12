package dev.catananti.service;

import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleI18n;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ArticleI18nRepository;
import dev.catananti.repository.ArticleRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for translating articles using the existing TranslationService (DeepL).
 * <p>
 * Translatable fields: title, subtitle, content, excerpt, seoTitle, seoDescription, seoKeywords.
 * Uses batch translation for efficiency (single API call for all fields).
 * </p>
 */
@Service
@Slf4j
public class ArticleTranslationService {

    private final ArticleRepository articleRepository;
    private final ArticleI18nRepository articleI18nRepository;
    private final TranslationService translationService;

    private final Cache<String, ArticleI18n> translationCache;

    public ArticleTranslationService(ArticleRepository articleRepository,
                                      ArticleI18nRepository articleI18nRepository,
                                      TranslationService translationService) {
        this.articleRepository = articleRepository;
        this.articleI18nRepository = articleI18nRepository;
        this.translationService = translationService;
        this.translationCache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofHours(1))
                .build();
    }

    /**
     * Check if translation service is available.
     */
    public boolean isAvailable() {
        return translationService.isAvailable();
    }

    /**
     * Translate an article to the target language and save the translation.
     *
     * @param articleId  the article ID
     * @param targetLang target language code (e.g., "pt-br", "es", "fr")
     * @return the saved ArticleI18n translation
     */
    @Transactional
    public Mono<ArticleI18n> translateArticle(Long articleId, String targetLang) {
        if (!isAvailable()) {
            return Mono.error(new IllegalStateException(
                    "Translation service not available. Configure DeepL API key."));
        }

        return articleRepository.findById(articleId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", articleId)))
                .flatMap(article -> {
                    // Flatten all translatable fields into a batch
                    List<String> texts = new ArrayList<>();
                    texts.add(article.getTitle());                          // 0
                    texts.add(nullToEmpty(article.getSubtitle()));          // 1
                    texts.add(article.getContent());                       // 2
                    texts.add(nullToEmpty(article.getExcerpt()));          // 3
                    texts.add(nullToEmpty(article.getSeoTitle()));         // 4
                    texts.add(nullToEmpty(article.getSeoDescription()));   // 5
                    texts.add(nullToEmpty(article.getSeoKeywords()));      // 6

                    log.info("Translating article {} ({}) to {}", articleId, article.getSlug(), targetLang);

                    return translationService.translateBatch(texts, targetLang)
                            .map(translated -> buildI18n(articleId, targetLang, translated, true));
                })
                .flatMap(i18n -> articleI18nRepository.upsert(i18n).thenReturn(i18n))
                .doOnSuccess(i18n -> {
                    // Drop any cached (stale) overlay so the fresh translation is served.
                    invalidateTranslationCache(articleId, targetLang);
                    log.info("Article {} translated to {}", articleId, targetLang);
                });
    }

    /**
     * Evict the in-memory translation overlay for an article+locale. Called whenever a
     * translation is (re-)created or deleted, so the public localized article does not keep
     * serving the previous/removed translation for up to the 1h cache TTL.
     */
    private void invalidateTranslationCache(Long articleId, String locale) {
        if (locale == null) {
            return;
        }
        // getTranslation() caches under the raw locale; applyTranslation() under the
        // lower-cased locale — evict both spellings.
        translationCache.invalidate(articleId + ":" + locale);
        translationCache.invalidate(articleId + ":" + locale.toLowerCase());
    }

    /**
     * Get available translations for an article.
     */
    public Flux<String> getAvailableLocales(Long articleId) {
        return articleI18nRepository.findLocalesByArticleId(articleId);
    }

    /**
     * Get a specific translation for an article.
     * Uses Caffeine cache with key=articleId+locale, TTL=1 hour.
     */
    public Mono<ArticleI18n> getTranslation(Long articleId, String locale) {
        String cacheKey = articleId + ":" + locale;
        ArticleI18n cached = translationCache.getIfPresent(cacheKey);
        if (cached != null) {
            return Mono.just(cached);
        }
        return articleI18nRepository.findByArticleIdAndLocale(articleId, locale)
                .doOnNext(i18n -> translationCache.put(cacheKey, i18n));
    }

    /**
     * Get all translations for an article.
     */
    public Flux<ArticleI18n> getTranslations(Long articleId) {
        return articleI18nRepository.findByArticleId(articleId);
    }

    /**
     * Delete a specific translation.
     */
    public Mono<Void> deleteTranslation(Long articleId, String locale) {
        return articleI18nRepository.deleteByArticleIdAndLocale(articleId, locale)
                .doOnSuccess(v -> invalidateTranslationCache(articleId, locale));
    }

    /**
     * Apply a translation overlay on top of an article's base fields.
     * If no translation exists for the given locale, returns the original article unchanged.
     * Uses Caffeine cache for performance.
     */
    public Mono<Article> applyTranslation(Article article, String locale) {
        if (locale == null || locale.isBlank() || locale.equalsIgnoreCase("en")) {
            return Mono.just(article);
        }

        String cacheKey = article.getId() + ":" + locale.toLowerCase();
        ArticleI18n cached = translationCache.getIfPresent(cacheKey);
        if (cached != null) {
            applyI18nToArticle(article, cached);
            return Mono.just(article);
        }

        return articleI18nRepository.findByArticleIdAndLocale(article.getId(), locale.toLowerCase())
                .doOnNext(i18n -> translationCache.put(cacheKey, i18n))
                .map(i18n -> {
                    applyI18nToArticle(article, i18n);
                    return article;
                })
                .defaultIfEmpty(article);
    }

    /**
     * NP-4: batch overlay for localized listings. Applies cached translations first
     * and fetches the remaining ones in a single IN query, so a page of N articles
     * costs at most 1 translation query instead of N.
     */
    public Mono<java.util.List<Article>> applyTranslations(java.util.List<Article> articles, String locale) {
        if (locale == null || locale.isBlank() || locale.equalsIgnoreCase("en") || articles.isEmpty()) {
            return Mono.just(articles);
        }
        String normalizedLocale = locale.toLowerCase();

        java.util.List<Article> misses = new java.util.ArrayList<>();
        for (Article article : articles) {
            ArticleI18n cached = translationCache.getIfPresent(article.getId() + ":" + normalizedLocale);
            if (cached != null) {
                applyI18nToArticle(article, cached);
            } else {
                misses.add(article);
            }
        }
        if (misses.isEmpty()) {
            return Mono.just(articles);
        }

        var missIds = misses.stream().map(Article::getId).toList();
        return articleI18nRepository.findByArticleIdsAndLocale(missIds, normalizedLocale)
                .collectList()
                .map(i18ns -> {
                    var byArticleId = i18ns.stream()
                            .collect(java.util.stream.Collectors.toMap(ArticleI18n::getArticleId, i -> i));
                    for (Article article : misses) {
                        ArticleI18n i18n = byArticleId.get(article.getId());
                        if (i18n != null) {
                            translationCache.put(article.getId() + ":" + normalizedLocale, i18n);
                            applyI18nToArticle(article, i18n);
                        }
                    }
                    return articles;
                });
    }

    private void applyI18nToArticle(Article article, ArticleI18n i18n) {
        article.setTitle(i18n.getTitle());
        if (i18n.getSubtitle() != null && !i18n.getSubtitle().isBlank()) {
            article.setSubtitle(i18n.getSubtitle());
        }
        article.setContent(i18n.getContent());
        if (i18n.getExcerpt() != null && !i18n.getExcerpt().isBlank()) {
            article.setExcerpt(i18n.getExcerpt());
        }
        if (i18n.getSeoTitle() != null && !i18n.getSeoTitle().isBlank()) {
            article.setSeoTitle(i18n.getSeoTitle());
        }
        if (i18n.getSeoDescription() != null && !i18n.getSeoDescription().isBlank()) {
            article.setSeoDescription(i18n.getSeoDescription());
        }
        if (i18n.getSeoKeywords() != null && !i18n.getSeoKeywords().isBlank()) {
            article.setSeoKeywords(i18n.getSeoKeywords());
        }
    }

    private ArticleI18n buildI18n(Long articleId, String locale, List<String> translated, boolean autoTranslated) {
        return ArticleI18n.builder()
                .articleId(articleId)
                .locale(locale.toLowerCase())
                .title(translated.get(0))
                .subtitle(emptyToNull(translated.get(1)))
                .content(translated.get(2))
                .excerpt(emptyToNull(translated.get(3)))
                .seoTitle(emptyToNull(translated.get(4)))
                .seoDescription(emptyToNull(translated.get(5)))
                .seoKeywords(emptyToNull(translated.get(6)))
                .autoTranslated(autoTranslated)
                .reviewed(false)
                .translatedAt(LocalDateTime.now())
                .build();
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private String emptyToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }
}
