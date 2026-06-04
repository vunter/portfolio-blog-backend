package dev.catananti.controller;

import dev.catananti.entity.User;
import dev.catananti.entity.UserRole;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Dashboard stats scoped by role:
 * - ADMIN sees global stats (all articles, all users, subscribers, etc.)
 * - DEV sees only their own articles, comments on their articles, tags on their articles.
 *   Users/subscribers stats are omitted (set to 0).
 *
 * <p>Aggregation logic lives in {@link DashboardService}; this controller only resolves
 * the authenticated caller and routes to the global or author-scoped view.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV')")
@Tag(name = "Admin - Dashboard", description = "Dashboard statistics and activity")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminDashboardController {

    private final DashboardService dashboardService;
    private final UserRepository userRepository;

    @GetMapping("/stats")
    @Operation(summary = "Get dashboard statistics", description = "Get overview statistics scoped by role")
    public Mono<Map<String, Object>> getDashboardStats() {
        log.debug("Fetching dashboard stats");
        return getCurrentUser().flatMap(user ->
                UserRole.ADMIN.matches(user.getRole())
                        ? dashboardService.getGlobalStats()
                        : dashboardService.getScopedStats(user.getId()));
    }

    @GetMapping("/activity")
    @Operation(summary = "Get recent activity", description = "Get recent activity feed scoped by role")
    public Mono<List<Map<String, Object>>> getRecentActivity() {
        log.debug("Fetching recent activity");
        return getCurrentUser().flatMap(user ->
                UserRole.ADMIN.matches(user.getRole())
                        ? dashboardService.getGlobalActivity()
                        : dashboardService.getScopedActivity(user.getId()));
    }

    // ==================== AUTH HELPER ====================

    private Mono<User> getCurrentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> auth.getName())
                .flatMap(userRepository::findByEmail);
    }
}
