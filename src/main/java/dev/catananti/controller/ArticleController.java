package dev.catananti.controller;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.ArticleService;
import dev.catananti.service.InteractionDeduplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/articles")
@Validated
@RequiredArgsConstructor
@Tag(name = "Articles", description = "Public article endpoints")
@Slf4j
public class ArticleController {

    private final ArticleService articleService;
    // F-065: Using Optional<> with @RequiredArgsConstructor — Spring auto-wraps absent beans
    private final Optional<InteractionDeduplicationService> deduplicationService;

    @GetMapping
    @Operation(summary = "Get published articles", description = "Get paginated list of published articles")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Articles retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid pagination parameters")
    })
    public Mono<PageResponse<ArticleResponse>> getPublishedArticles(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo) {
        log.debug("Fetching published articles: page={}, size={}, locale={}", page, size, locale);
        return articleService.getPublishedArticles(page, size, locale, sort, dateFrom, dateTo);
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get article by slug", description = "Get a single published article by its slug")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Article found"),
            @ApiResponse(responseCode = "404", description = "Article not found")
    })
    public Mono<ArticleResponse> getArticleBySlug(
            @PathVariable @Size(min = 1, max = 255) @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            @RequestParam(required = false) String locale) {
        log.info("Fetching article by slug='{}'", slug);
        return articleService.getPublishedArticleBySlug(slug, locale);
    }

    @GetMapping("/{slug}/related")
    @Operation(summary = "Get related articles", description = "Get articles related to the given article by shared tags")
    public Mono<List<ArticleResponse>> getRelatedArticles(
            @PathVariable @Size(min = 1, max = 255) @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            @Parameter(description = "Maximum number of related articles to return")
            @RequestParam(defaultValue = "4") @Min(1) @Max(20) int limit) {
        log.debug("Fetching related articles for slug='{}'", slug);
        return articleService.getRelatedArticles(slug, limit).collectList();
    }

    @PostMapping("/{slug}/view")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @Operation(summary = "Record article view", description = "Increment the view count for an article (deduplicated per IP)")
    public Mono<Void> incrementViews(
            @PathVariable @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            ServerHttpRequest request) {
        log.debug("View tracked for slug='{}'", slug);
        return deduplicationService
                .map(svc -> svc.recordViewIfNew(slug, request)
                        .flatMap(isNew -> isNew ? articleService.incrementViews(slug) : Mono.<Void>empty()))
                .orElseGet(() -> articleService.incrementViews(slug));
    }

    @PostMapping("/{slug}/like")
    @Operation(summary = "Like article", description = "Increment the like count for an article (deduplicated per IP)")
    public Mono<Map<String, Object>> likeArticle(
            @PathVariable @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            ServerHttpRequest request) {
        Mono<Void> likeMono = deduplicationService
                .map(svc -> svc.recordLikeIfNew(slug, request)
                        .flatMap(isNew -> isNew ? articleService.likeArticle(slug) : Mono.<Void>empty()))
                .orElseGet(() -> articleService.likeArticle(slug));

        return likeMono.then(articleService.getLikeCount(slug))
                .map(count -> Map.<String, Object>of("likeCount", count));
    }

    @GetMapping("/tag/{tagSlug}")
    @Operation(summary = "Get articles by tag", description = "Get published articles with a specific tag")
    // TODO F-068: Accept tags as a typed List<String> instead of comma-separated string for cleaner API contract
    public Mono<PageResponse<ArticleResponse>> getArticlesByTag(
            @PathVariable @Size(min = 1, max = 100) @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String tagSlug,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String locale) {
        return articleService.getArticlesByTag(tagSlug, page, size, locale);
    }
}
