package dev.catananti.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * API information and health endpoints.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "API Info", description = "API information and version")
@Slf4j
public class ApiInfoController {

    private final String appVersion;

    @Value("${app.name:Portfolio Blog API}")
    private String appName;

    @Value("${app.description:RESTful API for Portfolio Blog}")
    private String appDescription;

    public ApiInfoController(
            @Autowired(required = false) BuildProperties buildProperties,
            @Value("${app.version:2.0.0}") String fallbackVersion) {
        if (buildProperties != null) {
            this.appVersion = buildProperties.getVersion();
        } else {
            log.info("BuildProperties not available, using fallback version");
            this.appVersion = fallbackVersion;
        }
    }

    private final Instant startTime = Instant.now();

    @GetMapping
    @Operation(summary = "API Root", description = "Get API information and available versions")
    public Mono<ResponseEntity<Map<String, Object>>> getApiInfo() {
        log.debug("Fetching API info");
        return Mono.just(ResponseEntity.ok(Map.of(
                "name", appName,
                "description", appDescription,
                "versions", Map.of(
                        "v1", "/api/v1",
                        "latest", "/api/v1"
                ),
                "documentation", "/swagger-ui.html",
                "health", "/api/health"
        )));
    }

    @GetMapping("/v1")
    @Operation(summary = "API v1 Info", description = "Get API v1 information and endpoints")
    public Mono<ResponseEntity<Map<String, Object>>> getV1Info() {
        log.debug("Fetching API v1 info");
        return Mono.just(ResponseEntity.ok(Map.of(
                "version", "1.0",
                "status", "stable",
                // F-072: Removed admin endpoint paths from public response to avoid information leakage
                "endpoints", Map.of(
                        "articles", "/api/v1/articles",
                        "tags", "/api/v1/tags",
                        "search", "/api/v1/search",
                        "newsletter", "/api/v1/newsletter",
                        "rss", "/rss.xml",
                        "sitemap", "/sitemap.xml",
                        "status", "/api/v1/status"
                ),
                "auth", "/api/v1/auth"
        )));
    }

    @GetMapping("/health")
    @Operation(summary = "Health Check", description = "Get API health status")
    public Mono<ResponseEntity<Map<String, Object>>> healthCheck() {
        log.debug("Health check requested");
        return Mono.just(ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", LocalDateTime.now().toString(),
                "version", appVersion
        )));
    }

    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        long minutes = duration.toMinutes() % 60;
        long seconds = duration.getSeconds() % 60;
        
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        
        return sb.toString().trim();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
