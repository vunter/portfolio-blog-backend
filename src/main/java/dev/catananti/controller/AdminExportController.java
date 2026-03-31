package dev.catananti.controller;

import dev.catananti.dto.BlogExport;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.service.ExportImportService;
import dev.catananti.service.ExportImportService.ImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/export")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Export/Import", description = "Blog data export and import")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminExportController {

    private final ExportImportService exportImportService;
    private final ArticleRepository articleRepository;

    @Value("${app.export.max-import-size-bytes:2097152}")
    private int maxImportSize;

    @Value("${app.export.max-export-articles:10000}")
    private int maxExportArticles;

    @GetMapping
    @Operation(summary = "Export blog data", description = "Export all articles and tags as JSON")
    public Mono<ResponseEntity<BlogExport>> exportBlog(
            @Parameter(description = "Name of the person exporting")
            @RequestParam(defaultValue = "Admin") @jakarta.validation.constraints.Size(max = 100) @jakarta.validation.constraints.Pattern(regexp = "^[a-zA-Z0-9 _-]+$", message = "exportedBy must contain only alphanumeric characters, spaces, hyphens, and underscores") String exportedBy) {
        log.info("Exporting blog data");
        return checkExportLimit()
                .then(exportImportService.exportAll(exportedBy))
                .map(ResponseEntity::ok);
    }

    @GetMapping("/json")
    @Operation(summary = "Export as JSON file", description = "Download blog data as a JSON file")
    public Mono<ResponseEntity<String>> exportAsJsonFile(
            @RequestParam(defaultValue = "Admin") @jakarta.validation.constraints.Size(max = 100) @jakarta.validation.constraints.Pattern(regexp = "^[a-zA-Z0-9 _-]+$") String exportedBy) {
        log.info("Exporting blog data as JSON file");
        String filename = "blog-export-" + 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".json";
        
        return checkExportLimit()
                .then(exportImportService.exportToJson(exportedBy))
                .map(json -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                org.springframework.http.ContentDisposition.attachment()
                                        .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                                        .build().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json));
    }

    @GetMapping("/markdown")
    @Operation(summary = "Export as Markdown", description = "Export all articles as Markdown with YAML frontmatter")
    public Mono<ResponseEntity<Map<String, String>>> exportAsMarkdown() {
        log.info("Exporting blog data as Markdown");
        return checkExportLimit()
                .then(exportImportService.exportToMarkdown())
                .map(ResponseEntity::ok);
    }

    @PostMapping("/import")
    @Operation(summary = "Import blog data", description = "Import articles and tags from JSON")
    public Mono<ResponseEntity<Map<String, Object>>> importBlog(
            @RequestBody String jsonData,
            @Parameter(description = "Overwrite existing articles and tags")
            @RequestParam(defaultValue = "false") boolean overwrite) {
        log.info("Importing blog data: overwrite={}", overwrite);
        // SEC-03: Reject payloads exceeding max import size to prevent OOM
        // Q7.8: Use byte-length (not char-length) since UTF-8 chars can be 1-4 bytes
        if (jsonData == null || jsonData.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxImportSize) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    "message", "Import payload too large. Maximum size is 2 MB."
            )));
        }
        return exportImportService.importFromJson(jsonData, overwrite)
                .map(result -> ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(Map.of(
                        "message", "Import completed",
                        "articlesImported", result.articlesImported(),
                        "articlesTotal", result.articlesTotal(),
                        "tagsImported", result.tagsImported(),
                        "errors", result.errors()
                )));
    }

    @GetMapping("/stats")
    @Operation(summary = "Get export preview", description = "Get statistics about what will be exported")
    public Mono<ResponseEntity<BlogExport.BlogStats>> getExportStats() {
        log.debug("Fetching export statistics");
        return exportImportService.exportAll("preview")
                .map(BlogExport::getStats)
                .map(ResponseEntity::ok);
    }

    private Mono<Void> checkExportLimit() {
        return articleRepository.countAll()
                .flatMap(count -> {
                    if (count > maxExportArticles) {
                        return Mono.error(new IllegalStateException(
                                "Export limit exceeded. Maximum " + maxExportArticles + " articles allowed, found " + count));
                    }
                    return Mono.empty();
                });
    }
}
