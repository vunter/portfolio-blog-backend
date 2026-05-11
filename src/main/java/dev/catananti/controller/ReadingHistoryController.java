package dev.catananti.controller;

import dev.catananti.dto.PageResponse;
import dev.catananti.service.ReadingHistoryService;
import dev.catananti.service.ReadingHistoryService.ReadingHistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import dev.catananti.config.PaginationConfig;
import jakarta.validation.constraints.Min;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * REST controller for managing user reading history.
 */
@RestController
@RequestMapping("/api/v1/admin/reading-history")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Validated
@Tag(name = "Reading History", description = "User reading history management")
@Slf4j
public class ReadingHistoryController {

    private final ReadingHistoryService readingHistoryService;
    private final PaginationConfig paginationConfig;

    @GetMapping
    @Operation(summary = "Get reading history", description = "Get paginated reading history for the authenticated user")
    public Mono<PageResponse<ReadingHistoryResponse>> getReadingHistory(
            @AuthenticationPrincipal String email,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        size = paginationConfig.clampPageSize(size);
        log.debug("Fetching reading history for user: {}", PiiMasker.maskEmail(email));
        return readingHistoryService.getReadingHistory(email, page, size);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Clear reading history", description = "Delete all reading history for the authenticated user")
    public Mono<Void> clearHistory(@AuthenticationPrincipal String email) {
        log.info("Clearing reading history for user: {}", PiiMasker.maskEmail(email));
        return readingHistoryService.clearHistory(email);
    }
}
