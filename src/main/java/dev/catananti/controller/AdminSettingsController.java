package dev.catananti.controller;

import dev.catananti.service.SiteSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private static final int MAX_SETTINGS_ENTRIES = 50;
    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_VALUE_LENGTH = 5000;

    @GetMapping
    @Operation(summary = "Get application settings", description = "Retrieve current application settings")
    public Mono<Map<String, Object>> getSettings() {
        log.debug("Fetching site settings");
        return settingsService.getAllSettings();
    }

    // TODO F-135: Add audit trail for settings changes (who changed what and when)
    // TODO F-136: Add per-key validation rules (e.g., postsPerPage must be 1–100, emails must be valid)
    @PutMapping
    @Operation(summary = "Update application settings", description = "Update application settings")
    public Mono<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> settings) {
        log.info("Updating site settings");
        // SEC-10: Validate input size constraints
        if (settings.size() > MAX_SETTINGS_ENTRIES) {
            return Mono.error(new IllegalArgumentException("Too many settings entries (max " + MAX_SETTINGS_ENTRIES + ")"));
        }
        Map<String, Object> sanitized = settings.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().length() <= MAX_KEY_LENGTH)
                .filter(e -> e.getValue() == null || e.getValue().toString().length() <= MAX_VALUE_LENGTH)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        return settingsService.updateSettings(sanitized);
    }
}
