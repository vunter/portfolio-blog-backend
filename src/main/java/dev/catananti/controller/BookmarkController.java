package dev.catananti.controller;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.BookmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * REST controller for managing article bookmarks.
 * CQ-01: Refactored to use BookmarkService (Controller → Service → Repository pattern).
 * TODO F-079: Add pagination support to getAllBookmarks (currently returns all bookmarks)
 */
@RestController
@RequestMapping("/api/v1/bookmarks")
@RequiredArgsConstructor
@Validated
@Tag(name = "Bookmarks", description = "Article bookmark management")
@Slf4j
public class BookmarkController {

    private final BookmarkService bookmarkService;

    @GetMapping
    @Operation(summary = "Get bookmarked articles", description = "Get all bookmarked articles for the current visitor")
    public Mono<PageResponse<ArticleResponse>> getBookmarks(
            @RequestHeader("X-Visitor-Id") @Size(min = 8, max = 64)
            @Pattern(regexp = "^[a-zA-Z0-9-]+$", message = "Visitor ID must contain only alphanumeric characters and dashes")
            String visitorId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        log.debug("Fetching bookmarks: page={}, size={}", page, size);
        return bookmarkService.getBookmarks(visitorId, page, size);
    }

    @GetMapping("/{articleSlug}")
    @Operation(summary = "Check if article is bookmarked")
    public Mono<BookmarkStatus> isBookmarked(
            @RequestHeader("X-Visitor-Id") @Size(min = 8, max = 64)
            @Pattern(regexp = "^[a-zA-Z0-9-]+$", message = "Visitor ID must contain only alphanumeric characters and dashes")
            String visitorId,
            @PathVariable @Size(min = 1, max = 200) String articleSlug) {
        log.debug("Checking bookmark for articleSlug={}", articleSlug);
        return bookmarkService.isBookmarked(visitorId, articleSlug)
                .map(BookmarkStatus::new);
    }

    @PostMapping("/{articleSlug}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Bookmark an article")
    public Mono<BookmarkStatus> addBookmark(
            @RequestHeader("X-Visitor-Id") @Size(min = 8, max = 64)
            @Pattern(regexp = "^[a-zA-Z0-9-]+$", message = "Visitor ID must contain only alphanumeric characters and dashes")
            String visitorId,
            @PathVariable @Size(min = 1, max = 200) String articleSlug) {
        log.info("Adding bookmark for articleSlug={}", articleSlug);
        return bookmarkService.addBookmark(visitorId, articleSlug)
                .map(BookmarkStatus::new);
    }

    @DeleteMapping("/{articleSlug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a bookmark")
    public Mono<Void> removeBookmark(
            @RequestHeader("X-Visitor-Id") @Size(min = 8, max = 64)
            @Pattern(regexp = "^[a-zA-Z0-9-]+$", message = "Visitor ID must contain only alphanumeric characters and dashes")
            String visitorId,
            @PathVariable @Size(min = 1, max = 200) String articleSlug) {
        log.info("Removing bookmark for articleSlug={}", articleSlug);
        return bookmarkService.removeBookmark(visitorId, articleSlug);
    }

    /** Simple response indicating bookmark status. */
    public record BookmarkStatus(boolean bookmarked) {}
}
