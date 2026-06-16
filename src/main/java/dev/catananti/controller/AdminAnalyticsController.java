package dev.catananti.controller;

import dev.catananti.dto.AnalyticsComparison;
import dev.catananti.dto.AnalyticsSummary;
import dev.catananti.dto.SearchAnalyticsResponse;
import dev.catananti.entity.UserRole;
import dev.catananti.service.AnalyticsService;
import dev.catananti.service.CurrentUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV')")
@Validated
@Tag(name = "Admin - Analytics", description = "Analytics and statistics endpoints")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminAnalyticsController {

    private final AnalyticsService analyticsService;
    private final CurrentUserService currentUserService;

    @GetMapping("/summary")
    @Operation(summary = "Get analytics summary", description = "Get analytics summary scoped by role")
    public Mono<AnalyticsSummary> getAnalyticsSummary(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        log.debug("Fetching analytics summary for days={}", days);
        return getScopedAnalytics(days);
    }

    @GetMapping
    @Operation(summary = "Get analytics", description = "Get analytics with period string scoped by role")
    public Mono<AnalyticsSummary> getAnalytics(
            @RequestParam(defaultValue = "30d") String period) {
        log.debug("Fetching analytics for period={}", period);
        int days = parsePeriod(period);
        return getScopedAnalytics(days);
    }

    private Mono<AnalyticsSummary> getScopedAnalytics(int days) {
        return getCurrentUser().flatMap(user -> {
            if (UserRole.ADMIN.matches(user.getRole())) {
                return analyticsService.getAnalyticsSummary(days);
            } else {
                return analyticsService.getAnalyticsSummaryByAuthor(days, user.getId());
            }
        });
    }

    @GetMapping("/compare")
    @Operation(summary = "Get period comparison", description = "Get current vs previous period metrics scoped by role")
    public Mono<AnalyticsComparison> getAnalyticsComparison(
            @RequestParam(defaultValue = "30d") String period) {
        log.debug("Fetching analytics comparison for period={}", period);
        int days = parsePeriod(period);
        return getCurrentUser().flatMap(user -> {
            if (UserRole.ADMIN.matches(user.getRole())) {
                return analyticsService.getAnalyticsComparison(days);
            } else {
                return analyticsService.getAnalyticsComparisonByAuthor(days, user.getId());
            }
        });
    }

    @GetMapping("/search")
    @Operation(summary = "Get search analytics", description = "Get search query analytics for the last N days")
    public Mono<SearchAnalyticsResponse> getSearchAnalytics(
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
        log.debug("Fetching search analytics for last {} days", days);
        return analyticsService.getSearchAnalytics(days, 10);
    }

    // ARCH-3: delegates to the shared CurrentUserService.
    private Mono<dev.catananti.entity.User> getCurrentUser() {
        return currentUserService.currentUser();
    }

    private int parsePeriod(String period) {
        if (period == null || period.isBlank()) return 30;
        String cleaned = period.trim().toLowerCase();
        if (cleaned.endsWith("d")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        try {
            int days = Integer.parseInt(cleaned);
            return Math.max(1, Math.min(365, days));
        } catch (NumberFormatException e) {
            log.debug("Invalid period format '{}', defaulting to 30 days", period);
            return 30;
        }
    }
}
