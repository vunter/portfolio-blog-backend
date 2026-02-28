package dev.catananti.controller;

import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.dto.SearchRequest;
import dev.catananti.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Validated
@Tag(name = "Search", description = "Article search endpoints")
@Slf4j
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    @Operation(summary = "Search articles", description = "Search published articles with filters and pagination")
    public Mono<PageResponse<ArticleResponse>> search(
            @RequestParam(required = false) @Size(max = 500, message = "Query too long") String q,
            @RequestParam(required = false) @Size(max = 10, message = "Maximum 10 tags") List<String> tags,
            @RequestParam(defaultValue = "date") @Pattern(regexp = "^(date|title|viewCount|views|likes)$", message = "Invalid sortBy value") String sortBy,
            @RequestParam(defaultValue = "desc") @Pattern(regexp = "^(asc|desc)$", message = "sortOrder must be 'asc' or 'desc'") String sortOrder,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        log.info("Search request: query='{}', page={}, size={}", q, page, size);
        log.info("[search-analytics] term='{}' tags={} sortBy={} page={}", q, tags, sortBy, page);

        SearchRequest request = SearchRequest.builder()
                .query(q)
                .tags(tags)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .page(page)
                .size(size)
                .locale(locale)
                .dateFrom(dateFrom)
                .dateTo(dateTo)
                .build();

        return searchService.searchArticles(request);
    }

    @GetMapping("/suggestions")
    @Operation(summary = "Get search suggestions", description = "Get autocomplete suggestions for search query")
    public Mono<List<String>> getSuggestions(
            @RequestParam @Size(min = 2, max = 100, message = "Query must be 2-100 characters") String q) {
        log.debug("Fetching suggestions for query='{}'", q);
        return searchService.getSuggestions(q).collectList();
    }
}
