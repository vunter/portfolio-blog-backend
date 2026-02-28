package dev.catananti.controller;

import dev.catananti.service.AuditService;
import dev.catananti.service.SiteSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin - Settings", description = "Application settings management")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminSettingsController {

    private final SiteSettingsService settingsService;
    private final AuditService auditService;

    private static final int MAX_SETTINGS_ENTRIES = 50;
    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_VALUE_LENGTH = 5000;

    @GetMapping
    @Operation(summary = "Get application settings", description = "Retrieve current application settings")
    public Mono<Map<String, Object>> getSettings() {
        log.debug("Fetching site settings");
        return settingsService.getAllSettings();
    }

    private static final Map<String, java.util.function.Predicate<Object>> VALIDATION_RULES = Map.of(
            "postsPerPage", v -> {
                try { int n = Integer.parseInt(v.toString()); return n >= 1 && n <= 100; }
                catch (NumberFormatException e) { return false; }
            },
            "commentsEnabled", v -> "true".equalsIgnoreCase(v.toString()) || "false".equalsIgnoreCase(v.toString()),
            "commentModeration", v -> "true".equalsIgnoreCase(v.toString()) || "false".equalsIgnoreCase(v.toString()),
            "newsletterEnabled", v -> "true".equalsIgnoreCase(v.toString()) || "false".equalsIgnoreCase(v.toString()),
            "analyticsEnabled", v -> "true".equalsIgnoreCase(v.toString()) || "false".equalsIgnoreCase(v.toString()),
            "maintenanceMode", v -> "true".equalsIgnoreCase(v.toString()) || "false".equalsIgnoreCase(v.toString())
    );

    @PutMapping
    @Operation(summary = "Update application settings", description = "Update application settings")
    public Mono<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> settings,
                                                     Authentication authentication) {
        log.info("Updating site settings");
        // SEC-10: Validate input size constraints
        if (settings.size() > MAX_SETTINGS_ENTRIES) {
            return Mono.error(new IllegalArgumentException("Too many settings entries (max " + MAX_SETTINGS_ENTRIES + ")"));
        }

        // F-136: Per-key validation
        for (var entry : settings.entrySet()) {
            var rule = VALIDATION_RULES.get(entry.getKey());
            if (rule != null && entry.getValue() != null && !rule.test(entry.getValue())) {
                return Mono.error(new IllegalArgumentException(
                        "Invalid value for setting '" + entry.getKey() + "': " + entry.getValue()));
            }
        }

        Map<String, Object> sanitized = settings.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().length() <= MAX_KEY_LENGTH)
                .filter(e -> e.getValue() == null || e.getValue().toString().length() <= MAX_VALUE_LENGTH)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

        // F-135: Audit trail for settings changes
        String email = authentication != null ? authentication.getName() : "unknown";
        return settingsService.updateSettings(sanitized)
                .flatMap(result -> auditService.logAction(
                        "SETTINGS_UPDATE", "SETTINGS", "site_settings",
                        null, email, "Updated keys: " + sanitized.keySet())
                        .thenReturn(result));
    }
}
