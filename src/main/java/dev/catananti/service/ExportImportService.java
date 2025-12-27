package dev.catananti.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.catananti.dto.ArticleExportData;
import dev.catananti.dto.BlogExport;
import dev.catananti.dto.BlogExport.BlogStats;
import dev.catananti.dto.BlogExport.TagExportData;
import dev.catananti.entity.Article;
import dev.catananti.entity.LocalizedText;
import dev.catananti.entity.Tag;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.ArticleTagRepository;
import dev.catananti.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for exporting and importing blog data.
 * TODO F-176: Add progress feedback for long-running export/import operations (e.g., SSE or polling)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExportImportService {

    private final ArticleRepository articleRepository;
    private final TagRepository tagRepository;
    private final ArticleTagRepository articleTagRepository;
    private final ObjectMapper objectMapper;
    private final IdService idService;
    private final HtmlSanitizerService htmlSanitizerService;

    /**
     * Export all blog data to JSON format.
     */
    public Mono<BlogExport> exportAll(String exportedBy) {
        return Mono.zip(
                exportArticles().collectList(),
                exportTags().collectList(),
                getBlogStats()
        ).map(tuple -> {
            List<ArticleExportData> articles = tuple.getT1();
            List<TagExportData> tags = tuple.getT2();
            BlogStats stats = tuple.getT3();

            return BlogExport.builder()
                    .version("2.0")
                    .exportedAt(LocalDateTime.now())
                    .exportedBy(exportedBy)
                    .stats(stats)
                    .articles(articles)
                    .tags(tags)
                    .metadata(Map.of(
                            "format", "json",
                            "encoding", "UTF-8"
                    ))
                    .build();
        }).doOnSuccess(export -> log.info("Exported {} articles and {} tags", 
                export.getArticles().size(), export.getTags().size()));
    }

    /**
     * Export all articles with batch-loaded tags (eliminates N+1 query).
     */
    private Flux<ArticleExportData> exportArticles() {
        return articleRepository.findAll()
                .collectList()
                .flatMapMany(articles -> {
                    if (articles.isEmpty()) return Flux.empty();
                    Long[] articleIds = articles.stream().map(Article::getId).toArray(Long[]::new);
                    // Single batch query for all article-tag mappings
                    return articleTagRepository.findTagIdsByArticleIds(articleIds)
                            .collectMultimap(pair -> pair[0], pair -> pair[1])
                            .flatMap(articleTagMap ->
                                // Collect all unique tag IDs and fetch tags in one query
                                tagRepository.findAllById(
                                    articleTagMap.values().stream()
                                        .flatMap(java.util.Collection::stream)
                                        .distinct()
                                        .toList()
                                ).collectMap(Tag::getId, Tag::getSlug)
                                .map(tagIdToSlug -> {
                                    // Map each article to its tag slugs
                                    return articles.stream().map(article -> {
                                        var tagIds = articleTagMap.getOrDefault(article.getId(), java.util.Collections.emptyList());
                                        var slugs = tagIds.stream()
                                                .map(tagIdToSlug::get)
                                                .filter(java.util.Objects::nonNull)
                                                .collect(Collectors.toUnmodifiableSet());
                                        return toExportData(article, slugs);
                                    }).toList();
                                })
                            )
                            .flatMapMany(Flux::fromIterable);
                });
    }

    /**
     * Export all tags.
     */
    private Flux<TagExportData> exportTags() {
        return tagRepository.findAll()
                .map(tag -> TagExportData.builder()
                        .name(tag.getName() != null ? tag.getName().getDefault() : null)
                        .slug(tag.getSlug())
                        .description(tag.getDescription() != null ? tag.getDescription().getDefault() : null)
                        .color(tag.getColor())
                        .build());
    }

    /**
     * Get blog statistics.
     * Uses aggregate SQL queries instead of loading all articles into memory.
     */
    private Mono<BlogStats> getBlogStats() {
        return Mono.zip(
                articleRepository.countAll(),
                articleRepository.countByStatus("PUBLISHED"),
                articleRepository.countByStatus("DRAFT"),
                articleRepository.countScheduled(),
                tagRepository.count(),
                articleRepository.sumViewsCount(),
                articleRepository.sumLikesCount()
        ).map(tuple -> BlogStats.builder()
                .totalArticles(tuple.getT1())
                .publishedArticles(tuple.getT2())
                .draftArticles(tuple.getT3())
                .scheduledArticles(tuple.getT4())
                .totalTags(tuple.getT5())
                .totalViews(tuple.getT6())
                .totalLikes(tuple.getT7())
                .build()
        );
    }

    /**
     * Export to JSON string.
     */
    public Mono<String> exportToJson(String exportedBy) {
        return exportAll(exportedBy)
                // F-174: Offload blocking JSON serialization to boundedElastic
                .flatMap(export -> Mono.fromCallable(() ->
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorMap(JsonProcessingException.class, e ->
                                new RuntimeException("Failed to serialize export data", e)));
    }

    /**
     * Export articles to Markdown files content (returns map of slug -> markdown content).
     * Uses batch tag loading to avoid N+1 queries.
     */
    public Mono<Map<String, String>> exportToMarkdown() {
        return articleRepository.findAll()
                .collectList()
                .flatMap(articles -> {
                    if (articles.isEmpty()) return Mono.just(Map.<String, String>of());
                    Long[] articleIds = articles.stream().map(Article::getId).toArray(Long[]::new);
                    return articleTagRepository.findTagIdsByArticleIds(articleIds)
                            .collectMultimap(pair -> pair[0], pair -> pair[1])
                            .flatMap(articleTagMap ->
                                tagRepository.findAllById(
                                    articleTagMap.values().stream()
                                        .flatMap(java.util.Collection::stream)
                                        .distinct()
                                        .toList()
                                ).collectMap(Tag::getId, Tag::getSlug)
                                .map(tagIdToSlug ->
                                    articles.stream().collect(Collectors.toUnmodifiableMap(
                                        Article::getSlug,
                                        article -> {
                                            var tagIds = articleTagMap.getOrDefault(article.getId(), java.util.Collections.emptyList());
                                            var slugs = tagIds.stream()
                                                    .map(tagIdToSlug::get)
                                                    .filter(java.util.Objects::nonNull)
                                                    .collect(Collectors.toUnmodifiableSet());
                                            return toMarkdown(article, slugs);
                                        }
                                    ))
                                )
                            );
                });
    }

    /**
     * Maximum allowed import JSON size (2 MB) — aligned with AdminExportController.
     */
    private static final int MAX_IMPORT_SIZE = 2 * 1024 * 1024;

    /**
     * Import blog data from JSON.
     * Uses the injected ObjectMapper with secure defaults.
     * Enforces a size limit to prevent DoS via large payloads.
     */
    @Transactional
    public Mono<ImportResult> importFromJson(String json, boolean overwrite) {
        if (json == null || json.isBlank()) {
            return Mono.error(new IllegalArgumentException("Import data cannot be empty"));
        }
        if (json.length() > MAX_IMPORT_SIZE) {
            return Mono.error(new IllegalArgumentException(
                    "Import data exceeds maximum allowed size of " + (MAX_IMPORT_SIZE / 1024 / 1024) + " MB"));
        }
        // F-174: Offload blocking JSON parse to boundedElastic to avoid blocking event loop
        return Mono.fromCallable(() -> objectMapper.readValue(json, BlogExport.class))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(JsonProcessingException.class, e -> new RuntimeException("Failed to parse import data", e))
                .flatMap(export -> importData(export, overwrite));
    }

    /**
     * Import blog data.
     */
    @Transactional
    public Mono<ImportResult> importData(BlogExport export, boolean overwrite) {
        return importTags(export.getTags(), overwrite)
                .then(importArticles(export.getArticles(), overwrite))
                .doOnSuccess(result -> log.info("Import completed: {} articles, {} tags", 
                        result.articlesImported(), result.tagsImported()));
    }

    private Mono<Void> importTags(List<TagExportData> tags, boolean overwrite) {
        return Flux.fromIterable(tags)
                .flatMap(tagData -> {
                    return tagRepository.findBySlug(tagData.getSlug())
                            .flatMap(existingTag -> {
                                if (overwrite) {
                                    existingTag.setName(LocalizedText.ofEnglish(tagData.getName()));
                                    existingTag.setDescription(tagData.getDescription() != null
                                            ? LocalizedText.ofEnglish(tagData.getDescription()) : null);
                                    existingTag.setColor(tagData.getColor());
                                    return tagRepository.save(existingTag);
                                }
                                return Mono.just(existingTag);
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                Tag newTag = Tag.builder()
                                        .id(idService.nextId())
                                        .name(LocalizedText.ofEnglish(tagData.getName()))
                                        .slug(tagData.getSlug())
                                        .description(tagData.getDescription() != null
                                                ? LocalizedText.ofEnglish(tagData.getDescription()) : null)
                                        .color(tagData.getColor())
                                        .createdAt(LocalDateTime.now())
                                        .build();
                                return tagRepository.save(newTag);
                            }));
                })
                .then();
    }

    private Mono<ImportResult> importArticles(List<ArticleExportData> articles, boolean overwrite) {
        return Flux.fromIterable(articles)
                .flatMap(articleData -> {
                    return articleRepository.findBySlug(articleData.getSlug())
                            .flatMap(existingArticle -> {
                                if (overwrite) {
                                    updateArticleFromExport(existingArticle, articleData);
                                    return articleRepository.save(existingArticle)
                                            .flatMap(saved -> reconnectTags(saved.getId(), articleData.getTagSlugs()))
                                            .thenReturn(1);
                                }
                                return Mono.just(0); // Skip existing
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                Article newArticle = createArticleFromExport(articleData);
                                return articleRepository.save(newArticle)
                                        .flatMap(saved -> reconnectTags(saved.getId(), articleData.getTagSlugs()))
                                        .thenReturn(1);
                            }));
                })
                .reduce(0, Integer::sum)
                .map(count -> {
                    long tagCount = articles.stream()
                            .map(ArticleExportData::getTagSlugs)
                            .filter(java.util.Objects::nonNull)
                            .mapToLong(java.util.Set::size)
                            .sum();
                    return new ImportResult(count, articles.size(), (int) tagCount, 0);
                });
    }

    /**
     * BUG-09: Reconnect article-tag relations during import.
     */
    private Mono<Void> reconnectTags(Long articleId, java.util.Set<String> tagSlugs) {
        if (tagSlugs == null || tagSlugs.isEmpty()) {
            return Mono.empty();
        }
        return articleTagRepository.deleteByArticleId(articleId)
                .then(Flux.fromIterable(tagSlugs)
                        .flatMap(slug -> tagRepository.findBySlug(slug)
                                .flatMap(tag -> articleTagRepository.insertArticleTag(articleId, tag.getId())))
                        .then());
    }

    private ArticleExportData toExportData(Article article, java.util.Set<String> tagSlugs) {
        return ArticleExportData.builder()
                .id(String.valueOf(article.getId()))
                .slug(article.getSlug())
                .title(article.getTitle())
                .subtitle(article.getSubtitle())
                .content(article.getContent())
                .excerpt(article.getExcerpt())
                .coverImageUrl(article.getCoverImageUrl())
                .status(article.getStatus())
                .publishedAt(article.getPublishedAt())
                .scheduledAt(article.getScheduledAt())
                .readingTimeMinutes(article.getReadingTimeMinutes())
                .viewsCount(article.getViewsCount())
                .likesCount(article.getLikesCount())
                .tagSlugs(tagSlugs)
                .seoTitle(article.getSeoTitle())
                .seoDescription(article.getSeoDescription())
                .seoKeywords(article.getSeoKeywords())
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .build();
    }

    private String toMarkdown(Article article, java.util.Set<String> tagSlugs) {
        StringBuilder sb = new StringBuilder();
        
        // YAML front matter
        sb.append("---\n");
        sb.append("title: \"").append(article.getTitle()).append("\"\n");
        sb.append("slug: ").append(article.getSlug()).append("\n");
        if (article.getSubtitle() != null) {
            sb.append("subtitle: \"").append(article.getSubtitle()).append("\"\n");
        }
        sb.append("status: ").append(article.getStatus()).append("\n");
        if (article.getPublishedAt() != null) {
            sb.append("publishedAt: ").append(article.getPublishedAt()).append("\n");
        }
        if (!tagSlugs.isEmpty()) {
            sb.append("tags: [").append(String.join(", ", tagSlugs)).append("]\n");
        }
        if (article.getExcerpt() != null) {
            sb.append("excerpt: \"").append(article.getExcerpt().replace("\"", "\\\"")).append("\"\n");
        }
        if (article.getCoverImageUrl() != null) {
            sb.append("coverImage: ").append(article.getCoverImageUrl()).append("\n");
        }
        sb.append("---\n\n");
        
        // Content
        sb.append(article.getContent());
        
        return sb.toString();
    }

    private static final Set<String> ALLOWED_STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

    private void updateArticleFromExport(Article article, ArticleExportData data) {
        article.setTitle(sanitizeImportField(data.getTitle()));
        article.setSubtitle(sanitizeImportField(data.getSubtitle()));
        article.setContent(htmlSanitizerService.sanitize(data.getContent()));
        article.setExcerpt(sanitizeImportField(data.getExcerpt()));
        article.setCoverImageUrl(data.getCoverImageUrl());
        // F-175: Validate imported article status against allowed values
        String status = data.getStatus();
        article.setStatus(status != null && ALLOWED_STATUSES.contains(status) ? status : "DRAFT");
        article.setSeoTitle(sanitizeImportField(data.getSeoTitle()));
        article.setSeoDescription(sanitizeImportField(data.getSeoDescription()));
        article.setSeoKeywords(sanitizeImportField(data.getSeoKeywords()));
        article.setUpdatedAt(LocalDateTime.now());
    }

    private Article createArticleFromExport(ArticleExportData data) {
        return Article.builder()
                .id(idService.nextId())
                .slug(data.getSlug())
                .title(sanitizeImportField(data.getTitle()))
                .subtitle(sanitizeImportField(data.getSubtitle()))
                .content(htmlSanitizerService.sanitize(data.getContent()))
                .excerpt(sanitizeImportField(data.getExcerpt()))
                .coverImageUrl(data.getCoverImageUrl())
                .status(data.getStatus() != null && ALLOWED_STATUSES.contains(data.getStatus()) ? data.getStatus() : "DRAFT")
                .publishedAt(data.getPublishedAt())
                .scheduledAt(data.getScheduledAt())
                .readingTimeMinutes(data.getReadingTimeMinutes())
                .viewsCount(0)
                .likesCount(0)
                .seoTitle(sanitizeImportField(data.getSeoTitle()))
                .seoDescription(sanitizeImportField(data.getSeoDescription()))
                .seoKeywords(sanitizeImportField(data.getSeoKeywords()))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Sanitize a plain-text import field by escaping HTML entities.
     */
    private String sanitizeImportField(String value) {
        return value != null ? htmlSanitizerService.escapeHtml(value) : null;
    }

    /**
     * Result of an import operation.
     */
    public record ImportResult(
            int articlesImported,
            int articlesTotal,
            int tagsImported,
            int errors
    ) {}
}
