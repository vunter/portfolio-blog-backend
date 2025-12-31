package dev.catananti.controller;

import dev.catananti.service.CacheService;
import dev.catananti.service.CacheService.CacheStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/cache")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Cache", description = "Cache management endpoints")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminCacheController {

    private final CacheService cacheService;

    @GetMapping("/stats")
    @Operation(summary = "Get cache statistics", description = "Get current cache entry counts by type")
    public Mono<ResponseEntity<CacheStats>> getCacheStats() {
        log.debug("Fetching cache stats");
        return cacheService.getCacheStats()
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/articles")
    @Operation(summary = "Invalidate all article caches", description = "Clear all cached article data")
    public Mono<ResponseEntity<Map<String, Object>>> invalidateAllArticles() {
        log.info("Invalidating all article caches");
        return cacheService.invalidateAllArticles()
                .map(count -> ResponseEntity.ok(Map.of(
                        "message", "Article cache invalidated",
                        "entriesRemoved", count
                )));
    }

    @DeleteMapping("/articles/{slug}")
    @Operation(summary = "Invalidate article cache by slug", description = "Clear cached data for a specific article")
    public Mono<ResponseEntity<Map<String, Object>>> invalidateArticle(@PathVariable String slug) {
        log.info("Invalidating article cache: slug={}", slug);
        return cacheService.invalidateArticle(slug)
                .map(count -> ResponseEntity.ok(Map.of(
                        "message", "Article cache invalidated",
                        "slug", slug,
                        "entriesRemoved", count
                )));
    }

    @DeleteMapping("/tags")
    @Operation(summary = "Invalidate all tag caches", description = "Clear all cached tag data")
    public Mono<ResponseEntity<Map<String, Object>>> invalidateAllTags() {
        log.info("Invalidating all tag caches");
        return cacheService.invalidateAllTags()
                .map(count -> ResponseEntity.ok(Map.of(
                        "message", "Tag cache invalidated",
                        "entriesRemoved", count
                )));
    }

    @DeleteMapping("/tags/{tagSlug}")
    @Operation(summary = "Invalidate articles cache by tag", description = "Clear cached articles for a specific tag")
    public Mono<ResponseEntity<Map<String, Object>>> invalidateArticlesByTag(@PathVariable String tagSlug) {
        log.info("Invalidating articles cache for tag: {}", tagSlug);
        return cacheService.invalidateArticlesByTag(tagSlug)
                .map(count -> ResponseEntity.ok(Map.of(
                        "message", "Tag articles cache invalidated",
                        "tagSlug", tagSlug,
                        "entriesRemoved", count
                )));
    }

    @DeleteMapping("/comments")
    @Operation(summary = "Invalidate all comment caches", description = "Clear all cached comment data")
    public Mono<ResponseEntity<Map<String, Object>>> invalidateAllComments() {
        log.info("Invalidating all comment caches");
        return cacheService.invalidateAllComments()
                .map(count -> ResponseEntity.ok(Map.of(
                        "message", "Comment cache invalidated",
                        "entriesRemoved", count
                )));
    }

    @DeleteMapping("/comments/{articleId}")
    @Operation(summary = "Invalidate comments cache for article", description = "Clear cached comments for a specific article")
    public Mono<ResponseEntity<Map<String, Object>>> invalidateComments(@PathVariable String articleId) {
        log.info("Invalidating comments cache for article: {}", articleId);
        return cacheService.invalidateComments(articleId)
                .map(count -> ResponseEntity.ok(Map.of(
                        "message", "Article comments cache invalidated",
                        "articleId", articleId,
                        "entriesRemoved", count
                )));
    }

    @DeleteMapping("/search")
    @Operation(summary = "Invalidate search cache", description = "Clear all cached search results")
    public Mono<ResponseEntity<Map<String, Object>>> invalidateSearchCache() {
        log.info("Invalidating search cache");
        return cacheService.invalidateSearchCache()
                .map(count -> ResponseEntity.ok(Map.of(
                        "message", "Search cache invalidated",
                        "entriesRemoved", count
                )));
    }

    @DeleteMapping("/feeds")
    @Operation(summary = "Invalidate feed caches", description = "Clear RSS and Sitemap cache")
    public Mono<ResponseEntity<Map<String, Object>>> invalidateFeedCache() {
        log.info("Invalidating feed cache");
        return cacheService.invalidateFeedCache()
                .map(count -> ResponseEntity.ok(Map.of(
                        "message", "Feed cache invalidated",
                        "entriesRemoved", count
                )));
    }

    @DeleteMapping("/all")
    @Operation(summary = "Invalidate all caches", description = "Clear all cached data")
    public Mono<ResponseEntity<Map<String, Object>>> invalidateAllCaches() {
        log.info("Invalidating all caches");
        return cacheService.invalidateAllCaches()
                .map(count -> ResponseEntity.ok(Map.of(
                        "message", "All caches invalidated",
                        "entriesRemoved", count
                )));
    }

    @DeleteMapping
    @Operation(summary = "Invalidate all caches (alias)", description = "Clear all cached data (alias for /all)")
    public Mono<ResponseEntity<Map<String, Object>>> invalidateAllCachesAlias() {
        log.info("Invalidating all caches (alias)");
        return invalidateAllCaches();
    }
}
