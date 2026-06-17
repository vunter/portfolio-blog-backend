package dev.catananti.service;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.dto.SearchRequest;
import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.SearchQuery;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.SearchQueryRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import dev.catananti.util.IpAddressExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// F-223: SQL table alias constants
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final Pattern TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9-]+$");
    private static final String ALIAS_ARTICLES = "a";
    private static final String ALIAS_ARTICLE_TAGS = "at";
    private static final String ALIAS_TAGS = "t";

    /**
     * Allowlist of sort-key → unqualified SQL column. New keys must be added here, never
     * built from raw input.
     */
    private static final java.util.Map<String, String> SORT_COLUMNS = java.util.Map.of(
            "date",      "published_at",
            "relevance", "published_at",
            "views",     "views_count",
            "viewcount", "views_count",
            "likes",     "likes_count",
            "title",     "title"
    );

    private final ArticleRepository articleRepository;
    private final TagRepository tagRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final SearchQueryRepository searchQueryRepository;
    private final IdService idService;

    @Value("${app.search.use-fts:false}")
    private boolean useFts;

    @Value("${app.search.max-tags:10}")
    private int maxTags;

    @Value("${app.search.max-suggestions:5}")
    private int maxSuggestions;

    /**
     * Generates the SQL condition for text search.
     * Q4.2: Multi-language search — matches against both the base article columns
     * AND any article_i18n translation. This lets users search in any language
     * and still find articles, regardless of their selected frontend locale.
     *
     * Uses PostgreSQL FTS with 'simple' config (no language-specific stemming)
     * so Portuguese/Italian/Spanish terms work as well as English.
     * Falls back to ILIKE for H2 compatibility (tests / dev).
     */
    /** Allow only short alphanumeric aliases — defensive guard against SQL-injection regressions. */
    private static final java.util.regex.Pattern SAFE_ALIAS = java.util.regex.Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,15}|");
    /** Param refs must be R2DBC positional placeholders (e.g. $1, $2). */
    private static final java.util.regex.Pattern SAFE_PARAM_REF = java.util.regex.Pattern.compile("\\$\\d{1,3}");

    private String ftsCondition(String tableAlias, String paramRef) {
        if (tableAlias == null || !SAFE_ALIAS.matcher(tableAlias).matches()) {
            throw new IllegalArgumentException("Unsafe SQL table alias rejected");
        }
        if (paramRef == null || !SAFE_PARAM_REF.matcher(paramRef).matches()) {
            throw new IllegalArgumentException("Unsafe SQL parameter reference rejected");
        }
        String alias = tableAlias.isEmpty() ? "" : tableAlias;
        String aliasRef = alias.isEmpty() ? "" : alias + ".";
        String idRef = alias.isEmpty() ? "id" : alias + ".id";

        if (useFts) {
            return ("""
                    (
                        to_tsvector('simple', coalesce(%stitle, '') || ' ' || coalesce(%sexcerpt, '') || ' ' || coalesce(%scontent, ''))
                            @@ plainto_tsquery('simple', %s)
                        OR EXISTS (
                            SELECT 1 FROM article_i18n i18n
                            WHERE i18n.article_id = %s
                              AND to_tsvector('simple', coalesce(i18n.title, '') || ' ' || coalesce(i18n.excerpt, '') || ' ' || coalesce(i18n.content, ''))
                                  @@ plainto_tsquery('simple', %s)
                        )
                    )
                    """).formatted(aliasRef, aliasRef, aliasRef, paramRef, idRef, paramRef);
        }
        return ("""
                (
                    LOWER(%stitle) LIKE LOWER('%%' || %s || '%%')
                    OR LOWER(%scontent) LIKE LOWER('%%' || %s || '%%')
                    OR LOWER(%sexcerpt) LIKE LOWER('%%' || %s || '%%')
                )
                """).formatted(aliasRef, paramRef, aliasRef, paramRef, aliasRef, paramRef);
    }

    public Mono<PageResponse<ArticleResponse>> searchArticles(SearchRequest request) {
        return searchArticles(request, null);
    }

    public Mono<PageResponse<ArticleResponse>> searchArticles(SearchRequest request, ServerHttpRequest httpRequest) {
        String query = request.getQuery() != null ? request.getQuery().trim() : "";
        // F-291: Sanitize LIKE special characters to prevent wildcard injection
        String sanitizedQuery = DigestUtils.escapeLikePattern(query);
        int offset = request.getPage() * request.getSize();
        log.debug("Searching articles: query='{}', page={}, size={}, tags={}", query, request.getPage(), request.getSize(), request.getTags());

        boolean hasDateFilter = request.getDateFrom() != null || request.getDateTo() != null;

        if (query.isEmpty() && (request.getTags() == null || request.getTags().isEmpty()) && !hasDateFilter) {
            return getRecentArticles(request.getPage(), request.getSize());
        }

        Flux<Article> articlesFlux;
        Mono<Long> countMono;

        LocalDateTime from = request.getDateFrom() != null ? request.getDateFrom().atStartOfDay() : null;
        LocalDateTime to = request.getDateTo() != null ? request.getDateTo().atTime(LocalTime.MAX) : null;

        if (request.getTags() != null && !request.getTags().isEmpty()) {
            // Search with tags filter (and optional date range)
            articlesFlux = searchByQueryAndTags(sanitizedQuery, request.getTags(), request.getSize(), offset, request.getSortBy(), from, to);
            countMono = countByQueryAndTags(sanitizedQuery, request.getTags(), from, to);
        } else if (hasDateFilter) {
            // Search with date range (using dynamic SQL). ftsCondition() inside already
            // covers article_i18n, so this path is multi-language.
            articlesFlux = searchByQueryAndDateRange(sanitizedQuery, from, to, request.getSize(), offset);
            countMono = countByQueryAndDateRange(sanitizedQuery, from, to);
        } else if (useFts) {
            // Q4.2: Query-only + Postgres — use FTS variant that also matches article_i18n translations.
            articlesFlux = articleRepository.searchByStatusAndQueryFts(
                    ArticleStatus.PUBLISHED.name(), sanitizedQuery, request.getSize(), offset);
            countMono = articleRepository.countSearchByStatusAndQueryFts(
                    ArticleStatus.PUBLISHED.name(), sanitizedQuery);
        } else {
            // H2 dev / test fallback — LIKE-based search on base columns only.
            articlesFlux = articleRepository.searchByStatusAndQuery(
                    ArticleStatus.PUBLISHED.name(), sanitizedQuery, request.getSize(), offset);
            countMono = articleRepository.countSearchByStatusAndQuery(
                    ArticleStatus.PUBLISHED.name(), sanitizedQuery);
        }

        return articlesFlux
                .collectList()
                .flatMap(this::batchEnrichArticles)
                .map(articles -> {
                    return articles.stream()
                            .map(this::toResponse)
                            .toList();
                })
                .zipWith(countMono)
                .map(tuple -> buildPageResponse(tuple.getT1(), tuple.getT2(), request.getPage(), request.getSize()))
                .flatMap(pageResponse -> {
                    if (!query.isEmpty()) {
                        return logSearchQuery(query, (int) pageResponse.getTotalElements(), httpRequest)
                                .thenReturn(pageResponse);
                    }
                    return Mono.just(pageResponse);
                });
    }

    private Flux<Article> searchByQueryAndTags(String query, List<String> tags, int limit, int offset, String sortBy,
                                                LocalDateTime dateFrom, LocalDateTime dateTo) {
        // Validate and sanitize tags - only allow alphanumeric and hyphens
        List<String> sanitizedTags = tags.stream()
                .filter(t -> t != null && TAG_PATTERN.matcher(t).matches())
                .limit(maxTags) // Limit number of tags to prevent abuse
                .toList();

        if (sanitizedTags.isEmpty()) {
            return Flux.empty();
        }

        String orderBy = "a." + SORT_COLUMNS.getOrDefault(
                sortBy != null ? sortBy.toLowerCase() : "date",
                "published_at") + " DESC";

        // Build parameterized query with positional parameters for tags
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT a.* FROM articles a
                JOIN article_tags at ON a.id = at.article_id
                JOIN tags t ON at.tag_id = t.id
                WHERE a.status = 'PUBLISHED'
                AND t.slug = ANY($1)
                AND """ + ftsCondition("a", "$2") + "\n");

        int paramIdx = 3;
        if (dateFrom != null) {
            sql.append(" AND a.published_at >= $").append(paramIdx++);
        }
        if (dateTo != null) {
            sql.append(" AND a.published_at <= $").append(paramIdx++);
        }

        sql.append(" ORDER BY ").append(orderBy);
        sql.append(" LIMIT $").append(paramIdx++).append(" OFFSET $").append(paramIdx);

        String[] tagArray = sanitizedTags.toArray(new String[0]);

        var spec = r2dbcTemplate.getDatabaseClient()
                .sql(sql.toString())
                .bind("$1", tagArray)
                .bind("$2", query != null ? query : "");

        int bindIdx = 3;
        if (dateFrom != null) {
            spec = spec.bind("$" + bindIdx++, dateFrom);
        }
        if (dateTo != null) {
            spec = spec.bind("$" + bindIdx++, dateTo);
        }
        spec = spec.bind("$" + bindIdx++, limit);
        spec = spec.bind("$" + bindIdx, offset);

        return spec.map((row, metadata) -> mapRowToArticle(row)).all();
    }

    private Mono<Long> countByQueryAndTags(String query, List<String> tags,
                                            LocalDateTime dateFrom, LocalDateTime dateTo) {
        // Validate and sanitize tags
        List<String> sanitizedTags = tags.stream()
                .filter(t -> t != null && TAG_PATTERN.matcher(t).matches())
                .limit(maxTags)
                .toList();

        if (sanitizedTags.isEmpty()) {
            return Mono.just(0L);
        }

        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(DISTINCT a.id) FROM articles a
                JOIN article_tags at ON a.id = at.article_id
                JOIN tags t ON at.tag_id = t.id
                WHERE a.status = 'PUBLISHED'
                AND t.slug = ANY($1)
                AND """ + ftsCondition("a", "$2") + "\n");

        int paramIdx = 3;
        if (dateFrom != null) {
            sql.append(" AND a.published_at >= $").append(paramIdx++);
        }
        if (dateTo != null) {
            sql.append(" AND a.published_at <= $").append(paramIdx++);
        }

        String[] tagArray = sanitizedTags.toArray(new String[0]);

        var spec = r2dbcTemplate.getDatabaseClient()
                .sql(sql.toString())
                .bind("$1", tagArray)
                .bind("$2", query != null ? query : "");

        int bindIdx = 3;
        if (dateFrom != null) {
            spec = spec.bind("$" + bindIdx++, dateFrom);
        }
        if (dateTo != null) {
            spec = spec.bind("$" + bindIdx++, dateTo);
        }

        return spec.map((row, metadata) -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private Flux<Article> searchByQueryAndDateRange(String query, LocalDateTime dateFrom, LocalDateTime dateTo,
                                                     int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM articles
                WHERE status = 'PUBLISHED'
                """);

        int paramIdx = 1;
        boolean hasQuery = query != null && !query.isEmpty();

        if (hasQuery) {
            sql.append(" AND ").append(ftsCondition("", "$1"));
            paramIdx = 2;
        }

        if (dateFrom != null) {
            sql.append(" AND published_at >= $").append(paramIdx++);
        }
        if (dateTo != null) {
            sql.append(" AND published_at <= $").append(paramIdx++);
        }

        sql.append(" ORDER BY published_at DESC LIMIT $").append(paramIdx++).append(" OFFSET $").append(paramIdx);

        var spec = r2dbcTemplate.getDatabaseClient().sql(sql.toString());

        int bindIdx = 1;
        if (hasQuery) {
            spec = spec.bind("$" + bindIdx++, query);
        }
        if (dateFrom != null) {
            spec = spec.bind("$" + bindIdx++, dateFrom);
        }
        if (dateTo != null) {
            spec = spec.bind("$" + bindIdx++, dateTo);
        }
        spec = spec.bind("$" + bindIdx++, limit);
        spec = spec.bind("$" + bindIdx, offset);

        return spec.map((row, metadata) -> mapRowToArticle(row)).all();
    }

    private Mono<Long> countByQueryAndDateRange(String query, LocalDateTime dateFrom, LocalDateTime dateTo) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM articles
                WHERE status = 'PUBLISHED'
                """);

        int paramIdx = 1;
        boolean hasQuery = query != null && !query.isEmpty();

        if (hasQuery) {
            sql.append(" AND ").append(ftsCondition("", "$1"));
            paramIdx = 2;
        }

        if (dateFrom != null) {
            sql.append(" AND published_at >= $").append(paramIdx++);
        }
        if (dateTo != null) {
            sql.append(" AND published_at <= $").append(paramIdx++);
        }

        var spec = r2dbcTemplate.getDatabaseClient().sql(sql.toString());

        int bindIdx = 1;
        if (hasQuery) {
            spec = spec.bind("$" + bindIdx++, query);
        }
        if (dateFrom != null) {
            spec = spec.bind("$" + bindIdx++, dateFrom);
        }
        if (dateTo != null) {
            spec = spec.bind("$" + bindIdx++, dateTo);
        }

        return spec.map((row, metadata) -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private Mono<PageResponse<ArticleResponse>> getRecentArticles(int page, int size) {
        int offset = page * size;
        return articleRepository.findByStatusOrderByPublishedAtDesc(ArticleStatus.PUBLISHED.name(), size, offset)
                .collectList()
                .flatMap(this::batchEnrichArticles)
                .map(articles -> articles.stream().map(this::toResponse).toList())
                .zipWith(articleRepository.countByStatus(ArticleStatus.PUBLISHED.name()))
                .map(tuple -> buildPageResponse(tuple.getT1(), tuple.getT2(), page, size));
    }

    /**
     * Batch-enrich articles with tags, author names, and comment counts.
     * Uses 3 constant queries instead of 3*N (N+1 fix).
     * F-222: Enrichment logic is kept here since it is tightly coupled to SearchService's
     * query result format. Extract to ArticleEnrichmentHelper when reused by other services.
     */
    private Mono<List<Article>> batchEnrichArticles(List<Article> articles) {
        if (articles.isEmpty()) {
            return Mono.just(articles);
        }

        List<Long> articleIds = articles.stream().map(Article::getId).toList();
        java.util.Set<Long> authorIds = articles.stream()
                .map(Article::getAuthorId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        // Batch fetch tags for all articles
        Mono<java.util.Map<Long, java.util.Set<dev.catananti.entity.Tag>>> tagsMono = r2dbcTemplate.getDatabaseClient()
                .sql("""
                    SELECT at.article_id, t.id, t.name, t.slug, t.color
                    FROM tags t JOIN article_tags at ON t.id = at.tag_id
                    WHERE at.article_id = ANY($1)
                    """)
                .bind("$1", articleIds.toArray(new Long[0]))
                .map((row, meta) -> {
                    Long articleId = row.get("article_id", Long.class);
                    dev.catananti.entity.Tag tag = dev.catananti.entity.Tag.builder()
                            .id(row.get("id", Long.class))
                            .name(dev.catananti.entity.LocalizedText.fromJson(row.get("name", String.class)))
                            .slug(row.get("slug", String.class))
                            .color(row.get("color", String.class))
                            .build();
                    return java.util.Map.entry(articleId, tag);
                })
                .all()
                .collectList()
                .map(entries -> entries.stream().collect(
                        Collectors.groupingBy(java.util.Map.Entry::getKey,
                                Collectors.mapping(java.util.Map.Entry::getValue,
                                        java.util.stream.Collectors.toUnmodifiableSet()))));

        // Batch fetch author names
        Mono<java.util.Map<Long, String>> authorsMono = authorIds.isEmpty()
                ? Mono.just(java.util.Map.of())
                : userRepository.findAllById(authorIds)
                        .collectMap(u -> u.getId(), u -> u.getName());

        // Batch fetch comment counts
        Mono<java.util.Map<Long, Integer>> commentsMono = r2dbcTemplate.getDatabaseClient()
                .sql("""
                    SELECT article_id, COUNT(*) as cnt FROM comments
                    WHERE article_id = ANY($1) AND status = 'APPROVED'
                    GROUP BY article_id
                    """)
                .bind("$1", articleIds.toArray(new Long[0]))
                .map((row, meta) -> java.util.Map.entry(
                        row.get("article_id", Long.class),
                        row.get("cnt", Long.class).intValue()))
                .all()
                .collectMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue);

        return Mono.zip(tagsMono, authorsMono, commentsMono)
                .map(tuple -> {
                    var tagsMap = tuple.getT1();
                    var authorsMap = tuple.getT2();
                    var commentsMap = tuple.getT3();

                    for (Article article : articles) {
                        article.setTags(tagsMap.getOrDefault(article.getId(), java.util.Set.of()));
                        article.setAuthorName(article.getAuthorId() != null
                                ? authorsMap.getOrDefault(article.getAuthorId(), "Unknown")
                                : "Unknown");
                        article.setCommentCount(commentsMap.getOrDefault(article.getId(), 0));
                    }
                    return articles;
                });
    }

    private Article mapRowToArticle(io.r2dbc.spi.Row row) {
        return Article.builder()
                .id(row.get("id", Long.class))
                .slug(row.get("slug", String.class))
                .title(row.get("title", String.class))
                .subtitle(row.get("subtitle", String.class))
                .content(row.get("content", String.class))
                .excerpt(row.get("excerpt", String.class))
                .coverImageUrl(row.get("cover_image_url", String.class))
                .authorId(row.get("author_id", Long.class))
                .status(ArticleStatus.fromString(row.get("status", String.class), ArticleStatus.DRAFT))
                .publishedAt(row.get("published_at", java.time.LocalDateTime.class))
                .readingTimeMinutes(row.get("reading_time_minutes", Integer.class))
                .viewsCount(row.get("views_count", Integer.class))
                .likesCount(row.get("likes_count", Integer.class))
                .seoTitle(row.get("seo_title", String.class))
                .seoDescription(row.get("seo_description", String.class))
                .seoKeywords(row.get("seo_keywords", String.class))
                .createdAt(row.get("created_at", java.time.LocalDateTime.class))
                .updatedAt(row.get("updated_at", java.time.LocalDateTime.class))
                .build();
    }

    private ArticleResponse toResponse(Article article) {
        return ArticleResponse.builder()
                .id(String.valueOf(article.getId()))
                .slug(article.getSlug())
                .title(article.getTitle())
                .subtitle(article.getSubtitle())
                .excerpt(article.getExcerpt())
                .coverImageUrl(article.getCoverImageUrl())
                .author(ArticleResponse.AuthorInfo.builder()
                        .id(String.valueOf(article.getAuthorId()))
                        .name(article.getAuthorName() != null ? article.getAuthorName() : "Unknown")
                        .build())
                .status(article.getStatus().name())
                .publishedAt(article.getPublishedAt())
                .readingTimeMinutes(article.getReadingTimeMinutes())
                .viewCount(article.getViewsCount())
                .likeCount(article.getLikesCount())
                .commentCount(article.getCommentCount() != null ? article.getCommentCount() : 0)
                .tags(article.getTags() != null
                        ? article.getTags().stream()
                                .map(tag -> dev.catananti.dto.TagResponse.builder()
                                        .id(String.valueOf(tag.getId()))
                                        .name(tag.getName() != null ? tag.getName().getDefault() : null)
                                        .slug(tag.getSlug())
                                        .color(tag.getColor())
                                        .build())
                                .collect(Collectors.toUnmodifiableSet())
                        : java.util.Set.of())
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .build();
    }

    private PageResponse<ArticleResponse> buildPageResponse(List<ArticleResponse> content, Long total, int page, int size) {
        return PageResponse.of(content, page, size, total);
    }

    private Mono<Void> logSearchQuery(String query, int resultsCount, ServerHttpRequest httpRequest) {
        String anonymizedIp = httpRequest != null
                ? IpAddressExtractor.anonymizeIp(IpAddressExtractor.extractClientIp(httpRequest))
                : null;
        return searchQueryRepository.save(SearchQuery.builder()
                .id(idService.nextId())
                .queryText(query.trim().toLowerCase())
                .resultsCount(resultsCount)
                .userIp(anonymizedIp)
                .createdAt(LocalDateTime.now())
                .build())
                .doOnSuccess(saved -> log.debug("Search query logged: '{}' ({} results)", query, resultsCount))
                .onErrorResume(err -> {
                    log.warn("Failed to log search query: {}", err.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    public Flux<String> getSuggestions(String prefix) {
        if (prefix == null || prefix.length() < 2) {
            return Flux.empty();
        }
        log.debug("Getting search suggestions for prefix='{}'", prefix);

        String sql = """
                SELECT title FROM (
                    SELECT DISTINCT title, views_count FROM articles
                    WHERE status = 'PUBLISHED'
                    AND LOWER(title) LIKE LOWER($1)
                    ORDER BY views_count DESC
                    LIMIT $2
                ) AS subq
                """;

        return r2dbcTemplate.getDatabaseClient()
                .sql(sql)
                .bind("$1", DigestUtils.escapeLikePattern(prefix) + "%")
                .bind("$2", maxSuggestions)
                .map((row, metadata) -> row.get("title", String.class))
                .all();
    }
}
