package dev.catananti.controller;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.PdfGenerationRequest;
import dev.catananti.dto.ResumeTemplateRequest;
import dev.catananti.dto.ResumeTemplateResponse;
import dev.catananti.service.ResumeProfileService;
import dev.catananti.service.ResumeTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST Controller for managing resume templates and PDF generation.
 */
@RestController
@RequestMapping("/api/v1/resume")
@PreAuthorize("isAuthenticated()") // F-096: Enforce authentication at class level
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "Resume Templates", description = "APIs for managing resume HTML templates and generating PDFs")
public class ResumeTemplateController {

    private static final Pattern STYLE_TAG = Pattern.compile(
            "<style[^>]*>([\\s\\S]*?)</style>", Pattern.CASE_INSENSITIVE);

    private final ResumeTemplateService templateService;
    private final ResumeProfileService profileService;
    private final dev.catananti.service.PublicResumeService publicResumeService;
    private final dev.catananti.service.UserService userService;

    // ==================== Template CRUD ====================

    @Operation(summary = "Create a new resume template", 
               description = "Create a new HTML template for resume generation")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Template created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request data"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ResumeTemplateResponse> createTemplate(
            @Valid @RequestBody ResumeTemplateRequest request,
            Authentication authentication) {
        
        return extractUserId(authentication)
                .flatMap(userId -> templateService.createTemplate(userId, request))
                .doOnSuccess(t -> {
                    publicResumeService.clearPdfCache(null);
                    log.info("Template created: {} (PDF cache cleared)", t.getSlug());
                });
    }

    @Operation(summary = "Get template by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Template found"),
        @ApiResponse(responseCode = "404", description = "Template not found")
    })
    @GetMapping("/templates/{id}")
    public Mono<ResumeTemplateResponse> getTemplateById(
            @Parameter(description = "Template ID") @PathVariable Long id,
            Authentication authentication) {
        // F-097: Verify ownership to prevent IDOR
        return extractUserId(authentication)
                .flatMap(userId -> templateService.getTemplateById(id)
                        .flatMap(template -> verifyOwnership(template, userId)));
    }

    @Operation(summary = "Get template by slug")
    @GetMapping("/templates/slug/{slug}")
    public Mono<ResumeTemplateResponse> getTemplateBySlug(
            @Parameter(description = "Template slug") @PathVariable String slug,
            Authentication authentication) {
        // F-097: Verify ownership to prevent IDOR
        return extractUserId(authentication)
                .flatMap(userId -> templateService.getTemplateBySlug(slug)
                        .flatMap(template -> verifyOwnership(template, userId)));
    }

    @Operation(summary = "Update a template")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Template updated"),
        @ApiResponse(responseCode = "403", description = "Not authorized to update this template"),
        @ApiResponse(responseCode = "404", description = "Template not found")
    })
    @PutMapping("/templates/{id}")
    public Mono<ResumeTemplateResponse> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody ResumeTemplateRequest request,
            Authentication authentication) {
        
        return extractUserId(authentication)
                .flatMap(userId -> templateService.updateTemplate(id, userId, request))
                .doOnSuccess(response -> {
                    // Clear entire PDF cache since default template change affects public download
                    publicResumeService.clearPdfCache(null);
                    log.info("PDF cache cleared (template updated: {})", response.getSlug());
                });
    }

    @Operation(summary = "Apply profile data to an existing template",
               description = "Regenerates the template's HTML content from the user's stored profile data without creating a new template")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Template updated with profile data"),
        @ApiResponse(responseCode = "403", description = "Not authorized to update this template"),
        @ApiResponse(responseCode = "404", description = "Template or profile not found")
    })
    @PutMapping("/templates/{id}/apply-profile")
    public Mono<ResumeTemplateResponse> applyProfileToTemplate(
            @Parameter(description = "Template ID") @PathVariable Long id,
            @RequestParam(defaultValue = "en") String lang,
            Authentication authentication) {

        return extractUserId(authentication)
                .flatMap(userId -> profileService.generateResumeHtml(userId, lang)
                        .flatMap(generatedHtml -> {
                            // Extract CSS from the generated HTML for separate storage
                            String cssContent = "";
                            var styleMatch = STYLE_TAG.matcher(generatedHtml);
                            if (styleMatch.find()) {
                                cssContent = styleMatch.group(1).trim();
                            }

                            ResumeTemplateRequest updateRequest = new ResumeTemplateRequest();
                            updateRequest.setHtmlContent(generatedHtml);
                            updateRequest.setCssContent(cssContent);

                            return templateService.updateTemplate(id, userId, updateRequest);
                        }))
                .doOnSuccess(response -> {
                    publicResumeService.clearPdfCache(null);
                    log.info("Template {} updated from profile (PDF cache cleared)", response.getSlug());
                });
    }

    @Operation(summary = "Delete a template")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Template deleted"),
        @ApiResponse(responseCode = "403", description = "Not authorized to delete this template"),
        @ApiResponse(responseCode = "404", description = "Template not found")
    })
    @DeleteMapping("/templates/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTemplate(
            @PathVariable Long id,
            Authentication authentication) {
        
        return extractUserId(authentication)
                .flatMap(userId -> templateService.deleteTemplate(id, userId))
                .doOnSuccess(v -> {
                    publicResumeService.clearPdfCache(null);
                    log.info("Template deleted (PDF cache cleared)");
                })
                .then();
    }

    // ==================== List Operations ====================

    @Operation(summary = "Get all templates for the authenticated user")
    @GetMapping("/templates")
    public Mono<PageResponse<ResumeTemplateResponse>> getMyTemplates(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            Authentication authentication) {
        
        return extractUserId(authentication)
                .flatMap(userId -> templateService.getTemplatesByOwner(userId, page, size));
    }

    @Operation(summary = "Get templates by status")
    @GetMapping("/templates/status/{status}")
    public Mono<List<ResumeTemplateResponse>> getTemplatesByStatus(
            @PathVariable String status,
            Authentication authentication) {
        
        return extractUserId(authentication)
                .flatMapMany(userId -> templateService.getTemplatesByOwnerAndStatus(userId, status))
                .collectList();
    }

    @Operation(summary = "Search templates by name")
    @GetMapping("/templates/search")
    public Mono<List<ResumeTemplateResponse>> searchTemplates(
            @RequestParam String q,
            Authentication authentication) {
        
        return extractUserId(authentication)
                .flatMapMany(userId -> templateService.searchTemplates(userId, q))
                .collectList();
    }

    // ==================== INC-06: Duplicate & Preview ====================

    @Operation(summary = "Duplicate a template",
               description = "Creates a copy of an existing template with a new name/slug")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Template duplicated successfully"),
        @ApiResponse(responseCode = "404", description = "Source template not found")
    })
    @PostMapping("/templates/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ResumeTemplateResponse> duplicateTemplate(
            @PathVariable Long id,
            Authentication authentication) {
        
        return extractUserId(authentication)
                .flatMap(userId -> templateService.getTemplateById(id)
                        .flatMap(source -> {
                            ResumeTemplateRequest copyReq = new ResumeTemplateRequest();
                            copyReq.setName(source.getName() + " (Copy)");
                            copyReq.setHtmlContent(source.getHtmlContent());
                            copyReq.setCssContent(source.getCssContent());
                            copyReq.setStatus("DRAFT");
                            copyReq.setPaperSize(source.getPaperSize());
                            copyReq.setOrientation(source.getOrientation());
                            copyReq.setIsDefault(false);
                            return templateService.createTemplate(userId, copyReq);
                        }))
                .doOnSuccess(t -> log.info("Template duplicated: {} -> {}", id, t.getSlug()));
    }

    @Operation(summary = "Preview template HTML",
               description = "Returns the rendered HTML content of a template for inline preview")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "HTML preview returned",
                     content = @Content(mediaType = "text/html")),
        @ApiResponse(responseCode = "404", description = "Template not found")
    })
    @GetMapping(value = "/templates/{id}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> previewTemplate(@PathVariable Long id,
                                                         Authentication authentication) {
        // F-097: Verify ownership to prevent IDOR
        return extractUserId(authentication)
                .flatMap(userId -> templateService.getTemplateById(id)
                        .flatMap(template -> verifyOwnership(template, userId)))
                .map(template -> {
                    String html = template.getHtmlContent();
                    String css = template.getCssContent();
                    String fullHtml = css != null && !css.isBlank()
                            ? "<style>" + css + "</style>" + html
                            : html;
                    // F-098: Add CSP and security headers to prevent stored XSS
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data: https:;")
                            .header("X-Content-Type-Options", "nosniff")
                            .body(fullHtml);
                });
    }

    @Operation(summary = "Get most downloaded templates")
    @GetMapping("/templates/popular")
    public Mono<List<ResumeTemplateResponse>> getPopularTemplates(
            @RequestParam(defaultValue = "10") int limit) {
        return templateService.getMostDownloaded(limit).collectList();
    }

    @Operation(summary = "Get default template")
    @GetMapping("/templates/default")
    public Mono<ResumeTemplateResponse> getDefaultTemplate(
            Authentication authentication) {
        
        return extractUserId(authentication)
                .flatMap(templateService::getDefaultTemplate);
    }

    // ==================== PDF Generation ====================

    @Operation(summary = "Generate PDF from a saved template",
               description = "Convert a saved HTML template to PDF for download")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "PDF generated successfully",
                     content = @Content(mediaType = "application/pdf")),
        @ApiResponse(responseCode = "404", description = "Template not found")
    })
    @PostMapping(value = "/templates/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<byte[]>> generatePdfFromTemplate(
            @Parameter(description = "Template ID") @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> variables,
            Authentication authentication) {
        // F-097: Verify ownership to prevent IDOR
        return extractUserId(authentication)
                .flatMap(userId -> templateService.getTemplateById(id)
                        .flatMap(template -> verifyOwnership(template, userId)))
                .then(templateService.generatePdfFromTemplate(id, variables))
                .map(pdfBytes -> {
                    String filename = generateFilename("resume");
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdfBytes.length))
                            .body(pdfBytes);
                });
    }

    @Operation(summary = "Generate PDF from template slug")
    @PostMapping(value = "/templates/slug/{slug}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<byte[]>> generatePdfFromSlug(
            @PathVariable String slug,
            @RequestBody(required = false) Map<String, String> variables) {
        
        return templateService.generatePdfFromSlug(slug, variables)
                .map(pdfBytes -> {
                    String filename = generateFilename(slug);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                            .body(pdfBytes);
                });
    }

    @Operation(summary = "Generate PDF from raw HTML content",
               description = "Convert provided HTML content directly to PDF without saving as template")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "PDF generated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid HTML content")
    })
    @PostMapping(value = "/pdf/generate", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<byte[]>> generatePdfFromHtml(
            @Valid @RequestBody PdfGenerationRequest request) {
        
        return templateService.generatePdfFromHtml(request)
                .map(pdfBytes -> {
                    String filename = request.getFilename() != null 
                            ? ensurePdfExtension(request.getFilename())
                            : generateFilename("resume");
                    
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdfBytes.length))
                            .body(pdfBytes);
                });
    }

    @Operation(summary = "Preview PDF in browser",
               description = "Generate PDF and display inline in browser instead of downloading")
    @PostMapping(value = "/pdf/preview", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<byte[]>> previewPdf(
            @Valid @RequestBody PdfGenerationRequest request) {
        
        return templateService.generatePdfFromHtml(request)
                .map(pdfBytes -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                        .body(pdfBytes));
    }

    @Operation(summary = "Get default CSS styles",
               description = "Returns default CSS styles for resume templates")
    @GetMapping(value = "/css/default", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> getDefaultCss() {
        // F-101: Load CSS from resource file instead of inline string literal
        return Mono.fromCallable(() -> {
                    try (var is = getClass().getResourceAsStream("/templates/default-resume.css")) {
                        if (is == null) {
                            throw new IllegalStateException("Default CSS resource not found");
                        }
                        return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(defaultCss -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                        .body(defaultCss));
    }

    // ==================== Helper Methods ====================

    /**
     * F-097: Verify that the requesting user owns the template.
     * Prevents IDOR — any authenticated user could previously read/modify any template.
     */
    private Mono<ResumeTemplateResponse> verifyOwnership(ResumeTemplateResponse template, Long userId) {
        if (template.getOwnerId() != null && !template.getOwnerId().equals(String.valueOf(userId))) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to access this template"));
        }
        return Mono.just(template);
    }

    // TODO F-100: Extract extractUserId into a shared BaseController or utility
    private Mono<Long> extractUserId(Authentication authentication) {
        if (authentication == null) {
            return Mono.error(new IllegalStateException("User not authenticated"));
        }
        
        // JWT stores email as principal, so look up the user by email to get the ID
        String email = authentication.getName();
        return userService.getUserByEmail(email)
                .map(user -> Long.valueOf(user.getId()));
    }

    private String generateFilename(String base) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return base + "-" + timestamp + ".pdf";
    }

    private String ensurePdfExtension(String filename) {
        if (filename.toLowerCase().endsWith(".pdf")) {
            return filename;
        }
        return filename + ".pdf";
    }
}
