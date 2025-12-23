package dev.catananti.controller;

import dev.catananti.dto.PublicProfileSummary;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.service.PublicResumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * Public REST Controller for downloading resumes by alias (no authentication required).
 */
@RestController
@RequestMapping("/api/v1/public/resume")
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "Public Resume", description = "Public APIs for downloading resumes")
public class PublicResumeController {

    private static final Pattern CRLF = Pattern.compile("[\\r\\n\\t]");

    private final PublicResumeService publicResumeService;

    @Operation(summary = "List all published developer profiles",
               description = "Returns lightweight summaries of all developers with active resume profiles")
    @ApiResponse(responseCode = "200", description = "List of published profiles")
    @GetMapping(value = "/profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<PublicProfileSummary> getPublishedProfiles(
            @Parameter(description = "Language: en or pt")
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2})?$", message = "Invalid language format") String lang) {
        return publicResumeService.getPublishedProfiles(lang);
    }

    @Operation(summary = "Download resume PDF by alias",
               description = "Download a public resume PDF by the person's alias (e.g., 'leonardo-catananti')")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "PDF downloaded successfully"),
        @ApiResponse(responseCode = "404", description = "Resume not found for this alias")
    })
    // TODO F-089: Add server-side rate limiting for PDF generation (nginx zone or Spring RateLimiter)
    @GetMapping(value = "/{alias}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<byte[]>> downloadResumePdf(
            @Parameter(description = "Person alias (e.g., leonardo-catananti)") 
            @PathVariable @Size(max = 100) @jakarta.validation.constraints.Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "Invalid alias format") String alias,
            @Parameter(description = "Language: en or pt") 
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2})?$", message = "Invalid language format") String lang) {
        
        log.info("Public resume download requested for alias: {}, lang: {}", 
                CRLF.matcher(alias).replaceAll("_"), CRLF.matcher(lang).replaceAll("_"));
        
        return publicResumeService.generateResumePdf(alias, lang)
                .map(pdfBytes -> {
                    // F-090: Sanitize alias to prevent header injection via Content-Disposition
                    String safeAlias = alias.replaceAll("[^a-zA-Z0-9_-]", "");
                    String filename = safeAlias + "-resume-" + lang + ".pdf";
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdfBytes.length))
                            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                            .header(HttpHeaders.PRAGMA, "no-cache")
                            .body(pdfBytes);
                });
    }

    @Operation(summary = "Preview resume PDF in browser",
               description = "View a public resume PDF inline in the browser")
    @GetMapping(value = "/{alias}/preview", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<byte[]>> previewResumePdf(
            @PathVariable @Size(max = 100) @jakarta.validation.constraints.Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "Invalid alias format") String alias,
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2})?$", message = "Invalid language format") String lang) {
        
        return publicResumeService.generateResumePdf(alias, lang)
                .map(pdfBytes -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                        .body(pdfBytes));
    }

    @Operation(summary = "Get resume HTML by alias",
               description = "Get the raw HTML content of a public resume")
    @GetMapping(value = "/{alias}/html", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> getResumeHtml(
            @PathVariable @Size(max = 100) @jakarta.validation.constraints.Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "Invalid alias format") String alias,
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2})?$", message = "Invalid language format") String lang) {
        
        return publicResumeService.getResumeHtml(alias, lang)
                .map(html -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
                        .body(html));
    }

    @Operation(summary = "Get resume profile data by alias",
               description = "Get the structured JSON profile data for a public resume")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile data returned"),
        @ApiResponse(responseCode = "404", description = "Resume not found for this alias")
    })
    @GetMapping(value = "/{alias}/profile", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ResumeProfileResponse>> getResumeProfile(
            @Parameter(description = "Person alias (e.g., leonardo-catananti)")
            @PathVariable @Size(max = 100) @jakarta.validation.constraints.Pattern(regexp = "^[a-zA-Z0-9\\-_]+$", message = "Invalid alias format") String alias,
            @Parameter(description = "Language: en or pt")
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2})?$", message = "Invalid language format") String lang) {

        log.info("Public resume profile requested for alias: {}, lang: {}", 
                CRLF.matcher(alias).replaceAll("_"), lang != null ? CRLF.matcher(lang).replaceAll("_") : "null");

        return publicResumeService.getProfileByAlias(alias, lang)
                .map(ResponseEntity::ok);
    }
}
