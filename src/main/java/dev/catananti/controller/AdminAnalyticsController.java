package dev.catananti.controller;

import dev.catananti.dto.AnalyticsSummary;
import dev.catananti.entity.UserRole;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV', 'EDITOR')")
@Validated
@Tag(name = "Admin - Analytics", description = "Analytics and statistics endpoints")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminAnalyticsController {

    private final AnalyticsService analyticsService;
    private final UserRepository userRepository;

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

    private Mono<dev.catananti.entity.User> getCurrentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> auth.getName())
                .flatMap(email -> userRepository.findByEmail(email));
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
            return 30;
        }
    }
}
