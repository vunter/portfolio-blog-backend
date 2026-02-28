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

    /** SEC-03: Max import payload size (2 MB) */
    private static final int MAX_IMPORT_SIZE = 2 * 1024 * 1024;

    /** Max articles allowed in a single export to prevent OOM */
    private static final int MAX_EXPORT_ARTICLES = 10_000;

    @GetMapping
    @Operation(summary = "Export blog data", description = "Export all articles and tags as JSON")
    public Mono<ResponseEntity<BlogExport>> exportBlog(
            @Parameter(description = "Name of the person exporting")
            @RequestParam(defaultValue = "Admin") String exportedBy) {
        log.info("Exporting blog data");
        return checkExportLimit()
                .then(exportImportService.exportAll(exportedBy))
                .map(ResponseEntity::ok);
    }

    @GetMapping("/json")
    @Operation(summary = "Export as JSON file", description = "Download blog data as a JSON file")
    public Mono<ResponseEntity<String>> exportAsJsonFile(
            @RequestParam(defaultValue = "Admin") String exportedBy) {
        log.info("Exporting blog data as JSON file");
        String filename = "blog-export-" + 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".json";
        
        return checkExportLimit()
                .then(exportImportService.exportToJson(exportedBy))
                .map(json -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
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
        if (jsonData == null || jsonData.length() > MAX_IMPORT_SIZE) {
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
                    if (count > MAX_EXPORT_ARTICLES) {
                        return Mono.error(new IllegalStateException(
                                "Export limit exceeded. Maximum " + MAX_EXPORT_ARTICLES + " articles allowed, found " + count));
                    }
                    return Mono.empty();
                });
    }
}
