package dev.catananti.controller;

import dev.catananti.config.PaginationConfig;
import dev.catananti.config.LocaleConstants;
import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.AnalyticsService;
import dev.catananti.service.ArticleService;
import dev.catananti.service.InteractionDeduplicationService;
import dev.catananti.service.ReadingHistoryService;
import org.springframework.core.env.Environment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
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
    private final ReadingHistoryService readingHistoryService;
    private final AnalyticsService analyticsService;
    private final PaginationConfig paginationConfig;
    private final Environment environment;

    @PostConstruct
    void logDeduplicationStatus() {
        if (deduplicationService.isEmpty()) {
            if (List.of(environment.getActiveProfiles()).contains("prod")) {
                throw new IllegalStateException(
                        "InteractionDeduplicationService is required in production but not available. "
                        + "Ensure Redis (spring.cache.type=redis) is configured.");
            }
            log.warn("InteractionDeduplicationService is NOT available — view/like deduplication is DISABLED. "
                    + "Enable Redis (spring.cache.type=redis) for production use.");
        }
    }

    @GetMapping
    @Operation(summary = "Get published articles", description = "Get paginated list of published articles")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Articles retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid pagination parameters")
    })
    public Mono<PageResponse<ArticleResponse>> getPublishedArticles(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) String search) {
        size = paginationConfig.clampPageSize(size);
        validateLocale(locale);
        log.debug("Fetching published articles: page={}, size={}, locale={}, search={}", page, size, locale, search);
        return articleService.getPublishedArticles(page, size, locale, sort, dateFrom, dateTo, search);
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
        validateLocale(locale);
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
            @AuthenticationPrincipal String email,
            ServerHttpRequest request) {
        log.debug("View tracked for slug='{}'", slug);
        Mono<Void> viewMono = deduplicationService
                .map(svc -> svc.recordViewIfNew(slug, request)
                        .flatMap(isNew -> isNew ? articleService.incrementViews(slug) : Mono.<Void>empty()))
                .orElseGet(() -> articleService.incrementViews(slug));

        // Record analytics event for dashboard tracking
        Mono<Void> analyticsMono = analyticsService.trackArticleView(slug, request)
                .onErrorResume(e -> {
                    log.warn("Failed to record analytics for slug='{}': {}", slug, e.getMessage());
                    return Mono.empty();
                });

        // Track reading history for authenticated users
        if (email != null) {
            Mono<Void> historyMono = readingHistoryService.recordReadingByEmailAndSlug(email, slug)
                    .onErrorResume(e -> {
                        log.warn("Failed to record reading history for slug='{}': {}", slug, e.getMessage());
                        return Mono.empty();
                    });
            return viewMono.then(analyticsMono).then(historyMono);
        }
        return viewMono.then(analyticsMono);
    }

    @PostMapping("/{slug}/like")
    @Operation(summary = "Toggle article like", description = "Like or unlike an article (toggle, deduplicated per IP)")
    public Mono<Map<String, Object>> likeArticle(
            @PathVariable @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            ServerHttpRequest request) {

        Mono<Boolean> toggleMono = deduplicationService
                .map(svc -> svc.hasLiked(slug, request)
                        .flatMap(alreadyLiked -> {
                            if (alreadyLiked) {
                                return svc.removeLike(slug, request)
                                        .then(articleService.unlikeArticle(slug))
                                        .thenReturn(false);
                            } else {
                                return svc.recordLikeIfNew(slug, request)
                                        .then(articleService.likeArticle(slug))
                                        .thenReturn(true);
                            }
                        }))
                .orElseGet(() -> articleService.likeArticle(slug).thenReturn(true));

        return toggleMono.flatMap(liked -> articleService.getLikeCount(slug)
                .map(count -> Map.<String, Object>of("likeCount", count, "liked", liked)));
    }

    @GetMapping("/{slug}/like/status")
    @Operation(summary = "Check like status", description = "Check if the current user has already liked an article")
    public Mono<Map<String, Object>> getLikeStatus(
            @PathVariable @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String slug,
            ServerHttpRequest request) {
        Mono<Boolean> hasLikedMono = deduplicationService
                .map(svc -> svc.hasLiked(slug, request))
                .orElseGet(() -> Mono.just(false));

        return hasLikedMono.zipWith(articleService.getLikeCount(slug))
                .map(tuple -> Map.<String, Object>of("liked", tuple.getT1(), "likeCount", tuple.getT2()));
    }

    @GetMapping("/tag/{tagSlug}")
    @Operation(summary = "Get articles by tag", description = "Get published articles with a specific tag")
    // F-068: Multi-tag filtering is supported via the /api/v1/search endpoint (SearchRequest.tags).
    // Single-tag filtering uses a clean RESTful path variable pattern.
    public Mono<PageResponse<ArticleResponse>> getArticlesByTag(
            @PathVariable @Size(min = 1, max = 100) @Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid slug format") String tagSlug,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size,
            @RequestParam(required = false) String locale) {
        size = paginationConfig.clampPageSize(size);
        validateLocale(locale);
        return articleService.getArticlesByTag(tagSlug, page, size, locale);
    }

    private void validateLocale(String locale) {
        if (locale != null && !LocaleConstants.isSupported(locale)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported locale: " + locale + ". Supported: " + LocaleConstants.SUPPORTED_LOCALE_CODES);
        }
    }
}
