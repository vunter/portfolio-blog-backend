package dev.catananti.service;

import dev.catananti.dto.*;
import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.Tag;
import dev.catananti.entity.User;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.config.ResilienceConfig;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.repository.ArticleRepository;

import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
@Slf4j
// TODO F-145: Address N+1 query for tags — batch-fetch tags per page instead of per article
// TODO F-150: Guard against slug uniqueness race condition on concurrent article creation
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final TagRepository tagRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final UserRepository userRepository;
    private final ArticleTranslationService articleTranslationService;
    private final CommentRepository commentRepository;
    private final BlogMetrics blogMetrics;
    private final ResilienceConfig resilience;

    // ==================== PUBLIC ENDPOINTS ====================

    public Mono<PageResponse<ArticleResponse>> getPublishedArticles(int page, int size) {
        return getPublishedArticles(page, size, null);
    }

    public Mono<PageResponse<ArticleResponse>> getPublishedArticles(int page, int size, String locale) {
        return getPublishedArticles(page, size, locale, null);
    }

    public Mono<PageResponse<ArticleResponse>> getPublishedArticles(int page, int size, String locale, String sort) {
        return getPublishedArticles(page, size, locale, sort, null, null);
    }

    public Mono<PageResponse<ArticleResponse>> getPublishedArticles(int page, int size, String locale, String sort,
                                                                      LocalDate dateFrom, LocalDate dateTo) {
        int offset = page * size;
        String status = ArticleStatus.PUBLISHED.name();

        boolean hasDateFilter = dateFrom != null || dateTo != null;
        LocalDateTime from = dateFrom != null ? dateFrom.atStartOfDay() : LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime to = dateTo != null ? dateTo.atTime(LocalTime.MAX) : LocalDateTime.of(2099, 12, 31, 23, 59, 59);

        Flux<Article> articleFlux;
        Mono<Long> countMono;

        if (hasDateFilter) {
            articleFlux = articleRepository.findByStatusAndDateRangeOrderByPublishedAtDesc(status, from, to, size, offset);
            countMono = articleRepository.countByStatusAndDateRange(status, from, to);
        } else if (sort != null && sort.startsWith("viewCount")) {
            articleFlux = articleRepository.findByStatusOrderByViewsCountDesc(status, size, offset);
            countMono = articleRepository.countByStatus(status);
        } else {
            articleFlux = articleRepository.findByStatusOrderByPublishedAtDesc(status, size, offset);
            countMono = articleRepository.countByStatus(status);
        }

        return articleFlux
                .flatMap(article -> applyLocale(article, locale))
                .collectList()
                .flatMap(this::enrichArticlesWithMetadata)
                .zipWith(countMono)
                .map(tuple -> {
                    var content = tuple.getT1().stream().map(this::mapToResponse).toList();
                    var total = tuple.getT2();
                    return PageResponse.of(content, page, size, total);
                })
                .timeout(resilience.getDatabaseTimeout());
    }

    public Mono<ArticleResponse> getPublishedArticleBySlug(String slug) {
        return getPublishedArticleBySlug(slug, null);
    }

    public Mono<ArticleResponse> getPublishedArticleBySlug(String slug, String locale) {
        return articleRepository.findBySlugAndStatus(slug, ArticleStatus.PUBLISHED.name())
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "slug", slug)))
                .flatMap(article -> applyLocale(article, locale))
                .flatMap(this::enrichArticleWithMetadata)
                .map(this::mapToResponse)
                .timeout(resilience.getDatabaseTimeout());
    }

    @Transactional
    public Mono<Void> incrementViews(String slug) {
        return articleRepository.incrementViewsBySlug(slug)
                .doOnSuccess(_ -> blogMetrics.incrementArticleViews(slug));
    }

    @Transactional
    public Mono<Void> likeArticle(String slug) {
        return articleRepository.incrementLikesBySlug(slug)
                .doOnSuccess(_ -> {
                    blogMetrics.incrementArticleLikes(slug);
                    log.info("Article liked: {}", slug);
                });
    }

    /**
     * Get the current like count for an article by slug.
     */
    public Mono<Integer> getLikeCount(String slug) {
        return articleRepository.findBySlug(slug)
                .map(article -> article.getLikesCount() != null ? article.getLikesCount() : 0)
                .defaultIfEmpty(0);
    }

    // ==================== SEARCH ====================

    public Mono<PageResponse<ArticleResponse>> searchArticles(String query, int page, int size) {
        return searchArticles(query, page, size, null);
    }

    public Mono<PageResponse<ArticleResponse>> searchArticles(String query, int page, int size, String locale) {
        int offset = page * size;

        return articleRepository.searchByStatusAndQuery(ArticleStatus.PUBLISHED.name(), query, size, offset)
                .flatMap(article -> applyLocale(article, locale))
                .collectList()
                .flatMap(this::enrichArticlesWithMetadata)
                .zipWith(articleRepository.countSearchByStatusAndQuery(ArticleStatus.PUBLISHED.name(), query))
                .map(tuple -> {
                    var content = tuple.getT1().stream().map(this::mapToResponse).toList();
                    var total = tuple.getT2();
                    return PageResponse.of(content, page, size, total);
                })
                .timeout(resilience.getDatabaseTimeout());
    }

    public Mono<PageResponse<ArticleResponse>> getArticlesByTag(String tagSlug, int page, int size) {
        return getArticlesByTag(tagSlug, page, size, null);
    }

    public Mono<PageResponse<ArticleResponse>> getArticlesByTag(String tagSlug, int page, int size, String locale) {
        int offset = page * size;

        return articleRepository.findByTagSlugAndStatus(tagSlug, ArticleStatus.PUBLISHED.name(), size, offset)
                .flatMap(article -> applyLocale(article, locale))
                .collectList()
                .flatMap(this::enrichArticlesWithMetadata)
                .zipWith(articleRepository.countByTagSlugAndStatus(tagSlug, ArticleStatus.PUBLISHED.name()))
                .map(tuple -> {
                    var content = tuple.getT1().stream().map(this::mapToResponse).toList();
                    var total = tuple.getT2();
                    return PageResponse.of(content, page, size, total);
                })
                .timeout(resilience.getDatabaseTimeout());
    }

    /**
     * Enrich a single article with tags, author name, and comment count.
     */
    public Mono<Article> enrichArticleWithMetadata(Article article) {
        Mono<java.util.Set<Tag>> tagsMono = tagRepository.findByArticleId(article.getId())
                .collect(Collectors.toUnmodifiableSet());

        Mono<String> authorMono = article.getAuthorId() != null
                ? userRepository.findById(article.getAuthorId())
                        .map(User::getName)
                        .defaultIfEmpty("Unknown")
                : Mono.just("Unknown");

        Mono<Long> commentCountMono = commentRepository.countApprovedByArticleId(article.getId())
                .defaultIfEmpty(0L);

        return Mono.zip(tagsMono, authorMono, commentCountMono)
                // F-144: Use .map() instead of .doOnNext() to avoid mutation anti-pattern
                .map(tuple -> {
                    article.setTags(tuple.getT1());
                    article.setAuthorName(tuple.getT2());
                    article.setCommentCount(tuple.getT3().intValue());
                    return article;
                });
    }

    // ==================== BATCH ENRICHMENT (N+1 fix) ====================

    /**
     * Batch-enrich a list of articles with tags, author names, and comment counts.
     * Reduces N+1 queries (3*N) to 4 constant queries regardless of list size.
     */
    public Mono<List<Article>> enrichArticlesWithMetadata(List<Article> articles) {
        if (articles.isEmpty()) {
            return Mono.just(articles);
        }

        List<Long> articleIds = articles.stream().map(Article::getId).toList();
        Set<Long> authorIds = articles.stream()
                .map(Article::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());

        return Mono.zip(
                batchFetchTags(articleIds),
                batchFetchAuthors(authorIds),
                batchFetchCommentCounts(articleIds)
        ).map(tuple -> {
            Map<Long, Set<Tag>> tagsMap = tuple.getT1();
            Map<Long, User> authorsMap = tuple.getT2();
            Map<Long, Integer> commentsMap = tuple.getT3();

            for (Article article : articles) {
                article.setTags(tagsMap.getOrDefault(article.getId(), Set.of()));
                User author = article.getAuthorId() != null ? authorsMap.get(article.getAuthorId()) : null;
                if (author != null) {
                    article.setAuthorName(author.getName());
                    article.setAuthorEmail(author.getEmail());
                    article.setAuthorRole(author.getRole());
                } else {
                    article.setAuthorName("Unknown");
                }
                article.setCommentCount(commentsMap.getOrDefault(article.getId(), 0));
            }
            return articles;
        });
    }

    private Mono<Map<Long, Set<Tag>>> batchFetchTags(List<Long> articleIds) {
        // Step 1: Get article->tag mappings
        return r2dbcTemplate.getDatabaseClient()
                .sql("SELECT article_id, tag_id FROM article_tags WHERE article_id = ANY(:ids)")
                .bind("ids", articleIds.toArray(new Long[0]))
                .map((row, meta) -> Map.entry(
                        row.get("article_id", Long.class),
                        row.get("tag_id", Long.class)
                ))
                .all()
                .collectList()
                .flatMap(mappings -> {
                    Set<Long> tagIds = mappings.stream()
                            .map(Map.Entry::getValue)
                            .collect(Collectors.toUnmodifiableSet());
                    if (tagIds.isEmpty()) {
                        return Mono.just(Map.<Long, Set<Tag>>of());
                    }
                    // Step 2: Load all Tag entities (JSONB converters apply via repository)
                    return tagRepository.findAllById(tagIds)
                            .collectMap(Tag::getId)
                            .map(tagMap -> {
                                Map<Long, Set<Tag>> result = new HashMap<>();
                                for (var mapping : mappings) {
                                    Tag tag = tagMap.get(mapping.getValue());
                                    if (tag != null) {
                                        result.computeIfAbsent(mapping.getKey(), k -> new HashSet<>()).add(tag);
                                    }
                                }
                                return result;
                            });
                });
    }

    /**
     * Batch-fetch full User objects for enriching AuthorInfo.
     */
    private Mono<Map<Long, User>> batchFetchAuthors(Set<Long> authorIds) {
        if (authorIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return userRepository.findAllById(authorIds)
                .collectMap(User::getId);
    }

    private Mono<Map<Long, Integer>> batchFetchCommentCounts(List<Long> articleIds) {
        return r2dbcTemplate.getDatabaseClient()
                .sql("SELECT article_id, COUNT(*) as cnt FROM comments WHERE article_id = ANY(:ids) AND status = 'APPROVED' GROUP BY article_id")
                .bind("ids", articleIds.toArray(new Long[0]))
                .map((row, meta) -> Map.entry(
                        row.get("article_id", Long.class),
                        row.get("cnt", Long.class).intValue()
                ))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /**
     * Map an enriched Article entity to ArticleResponse DTO.
     */
    public ArticleResponse mapToResponse(Article article) {
        return ArticleResponse.builder()
                .id(String.valueOf(article.getId()))
                .slug(article.getSlug())
                .title(article.getTitle())
                .subtitle(article.getSubtitle())
                .content(article.getContent())
                .contentHtml(null) // Frontend renders markdown client-side via ngx-markdown
                .excerpt(article.getExcerpt())
                .coverImageUrl(article.getCoverImageUrl())
                .author(ArticleResponse.AuthorInfo.builder()
                        .id(String.valueOf(article.getAuthorId()))
                        .name(article.getAuthorName() != null ? article.getAuthorName() : "Unknown")
                        .avatarUrl(article.getAuthorAvatarUrl())
                        .build())
                .status(article.getStatus())
                .publishedAt(article.getPublishedAt())
                .scheduledAt(article.getScheduledAt())
                .readingTimeMinutes(article.getReadingTimeMinutes())
                .viewCount(article.getViewsCount())
                .likeCount(article.getLikesCount())
                .commentCount(article.getCommentCount())
                .tags(article.getTags().stream()
                        .map(this::mapTagToResponse)
                        .collect(Collectors.toUnmodifiableSet()))
                .seoTitle(article.getSeoTitle())
                .seoDescription(article.getSeoDescription())
                .seoKeywords(article.getSeoKeywords())
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .build();
    }

    private TagResponse mapTagToResponse(Tag tag) {
        return TagResponse.builder()
                .id(String.valueOf(tag.getId()))
                .name(tag.getName() != null ? tag.getName().getDefault() : null)
                .slug(tag.getSlug())
                .description(tag.getDescription() != null ? tag.getDescription().getDefault() : null)
                .color(tag.getColor())
                .names(tag.getName() != null ? tag.getName().getTranslations() : null)
                .descriptions(tag.getDescription() != null ? tag.getDescription().getTranslations() : null)
                .createdAt(tag.getCreatedAt())
                .updatedAt(tag.getUpdatedAt())
                .build();
    }

    // ==================== RELATED ARTICLES ====================

    /**
     * Find articles related to the given article by shared tags.
     * Falls back to recent articles if no related articles by tags are found.
     */
    public Flux<ArticleResponse> getRelatedArticles(String slug, int limit) {
        return articleRepository.findBySlugAndStatus(slug, ArticleStatus.PUBLISHED.name())
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "slug", slug)))
                .flatMapMany(article ->
                    articleRepository.findRelatedArticles(article.getId(), limit)
                            .switchIfEmpty(articleRepository.findRecentPublishedExcluding(article.getId(), limit))
                            .flatMap(this::enrichArticleWithMetadata)
                            .map(this::mapToResponse)
                );
    }

    // ==================== LOCALE SUPPORT ====================

    /**
     * Apply locale-specific translation overlay on an article.
     * If locale is null, empty, or "en", returns the article unchanged.
     */
    private Mono<Article> applyLocale(Article article, String locale) {
        if (locale == null || locale.isBlank() || locale.equalsIgnoreCase("en")) {
            return Mono.just(article);
        }
        return articleTranslationService.applyTranslation(article, locale);
    }

    /**
     * MIN-07: Service-layer access for feed controllers (Sitemap, RSS).
     * Returns raw Article entities for published articles, ordered by publishedAt desc.
     */
    public Flux<Article> findAllPublishedForFeed() {
        return articleRepository.findAllPublishedOrderByPublishedAtDesc();
    }
}
