package dev.catananti.controller;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.SubscriberResponse;
import dev.catananti.service.NewsletterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/newsletter")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin - Newsletter", description = "Newsletter subscriber management")
@SecurityRequirement(name = "Bearer Authentication")
// TODO F-131: Add confirmation step before batch operations (delete all, send to all)
@Slf4j
public class AdminNewsletterController {

    private final NewsletterService newsletterService;

    @GetMapping("/subscribers")
    @Operation(summary = "Get all subscribers", description = "Get paginated newsletter subscribers, with optional status and email filters")
    public Mono<PageResponse<SubscriberResponse>> getAllSubscribers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.debug("Fetching subscribers: status={}, email={}, page={}, size={}", status, email, page, size);
        return newsletterService.getAllSubscribersPaginated(status, email, page, size);
    }

    @GetMapping("/stats")
    @Operation(summary = "Get newsletter statistics", description = "Get subscriber counts and statistics")
    public Mono<Map<String, Long>> getStats() {
        log.debug("Fetching newsletter statistics");
        return newsletterService.getStats();
    }

    @DeleteMapping("/subscribers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete subscriber", description = "Remove a subscriber by ID")
    public Mono<Void> deleteSubscriber(@PathVariable Long id) {
        log.info("Deleting subscriber: id={}", id);
        return newsletterService.deleteSubscriber(id);
    }

    @PostMapping("/subscribers/delete-batch")
    @Operation(summary = "Batch delete subscribers", description = "Remove multiple subscribers by IDs (max 1000)")
    public Mono<Map<String, Object>> deleteBatch(@RequestBody Map<String, List<Long>> request) {
        List<Long> ids = request.getOrDefault("ids", List.of());
        log.info("Batch deleting subscribers: count={}", ids.size());
        if (ids.size() > 1000) {
            return Mono.just(Map.of("message", "Too many IDs. Maximum is 1000.", "count", (Object) 0));
        }
        return newsletterService.deleteSubscribersBatch(ids)
                .map(count -> Map.of("message", "Subscribers deleted", "count", (Object) count));
    }

    @GetMapping(value = "/export", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Export subscribers as CSV", description = "Export all confirmed subscribers as CSV")
    public Mono<String> exportSubscribers() {
        log.info("Exporting subscribers as CSV");
        return newsletterService.getActiveSubscribers()
                .map(s -> sanitizeCsvField(s.getEmail()) + "," + sanitizeCsvField(s.getName() != null ? s.getName() : "") + "," + sanitizeCsvField(s.getStatus()) + "," + s.getCreatedAt())
                .collectList()
                .map(lines -> {
                    StringBuilder csv = new StringBuilder("email,name,status,subscribed_at\n");
                    lines.forEach(line -> csv.append(line).append("\n"));
                    return csv.toString();
                });
    }

    /**
     * Sanitize CSV field to prevent formula injection (SEC-02).
     * Prefixes fields starting with =, +, -, @, |, or % with a single quote.
     */
    private String sanitizeCsvField(String value) {
        if (value == null || value.isEmpty()) return "";
        String trimmed = value.trim();
        if (trimmed.startsWith("=") || trimmed.startsWith("+") || trimmed.startsWith("-") 
                || trimmed.startsWith("@") || trimmed.startsWith("|") || trimmed.startsWith("%")) {
            trimmed = "'" + trimmed;
        }
        // Escape quotes and wrap in quotes if contains comma
        if (trimmed.contains(",") || trimmed.contains("\"")) {
            trimmed = "\"" + trimmed.replace("\"", "\"\"") + "\"";
        }
        return trimmed;
    }
}
