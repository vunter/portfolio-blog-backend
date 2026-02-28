package dev.catananti.service;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.PdfGenerationRequest;
import dev.catananti.dto.ResumeTemplateRequest;
import dev.catananti.dto.ResumeTemplateResponse;
import dev.catananti.entity.LocalizedText;
import dev.catananti.entity.ResumeTemplate;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ResumeTemplateRepository;
import dev.catananti.util.DigestUtils;
import dev.catananti.repository.UserRepository;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Service for managing resume templates and PDF generation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeTemplateService {

    private static final Pattern NON_SLUG_ALPHA = Pattern.compile("[^a-z0-9\\s-]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern MULTI_HYPHEN = Pattern.compile("-+");
    private static final Pattern LEADING_TRAILING_HYPHENS = Pattern.compile("^-|-$");
    private static final Pattern ALIAS_CHARS = Pattern.compile("[^a-z0-9-]");

    private final ResumeTemplateRepository templateRepository;
    private final UserRepository userRepository;
    private final PdfGenerationService pdfGenerationService;
    private final IdService idService;
    private final DatabaseClient databaseClient;
    private final org.springframework.core.env.Environment environment;

    // ==================== CRUD Operations ====================

    /**
     * Create a new resume template.
     */
    @Transactional
    public Mono<ResumeTemplateResponse> createTemplate(Long ownerId, ResumeTemplateRequest request) {
        return validateHtmlContent(request.getHtmlContent())
                .flatMap(valid -> {
                    if (!valid) {
                        return Mono.error(new IllegalArgumentException("Invalid HTML content"));
                    }

                    String slug = generateSlug(request.getName());
                    
                    return ensureUniqueSlug(slug)
                            .flatMap(uniqueSlug -> {
                                // Normalize alias if provided
                                String alias = request.getAlias() != null && !request.getAlias().isBlank()
                                        ? ALIAS_CHARS.matcher(request.getAlias().toLowerCase()).replaceAll("")
                                        : null;

                                ResumeTemplate template = ResumeTemplate.builder()
                                        .id(idService.nextId())
                                        .slug(uniqueSlug)
                                        .alias(alias)
                                        .name(LocalizedText.ofEnglish(request.getName()))
                                        .description(request.getDescription() != null
                                                ? LocalizedText.ofEnglish(request.getDescription()) : null)
                                        .htmlContent(request.getHtmlContent())
                                        .cssContent(request.getCssContent())
                                        .status(request.getStatus() != null ? request.getStatus() : "DRAFT")
                                        .ownerId(ownerId)
                                        .version(1)
                                        .isDefault(request.getIsDefault() != null && request.getIsDefault())
                                        .paperSize(request.getPaperSize() != null ? request.getPaperSize() : "A4")
                                        .orientation(request.getOrientation() != null ? request.getOrientation() : "PORTRAIT")
                                        .downloadCount(0)
                                        .createdAt(LocalDateTime.now())
                                        .updatedAt(LocalDateTime.now())
                                        .build();

                                // If setting as default, reset other defaults
                                Mono<Void> resetDefault = template.getIsDefault()
                                        ? templateRepository.resetDefaultForOwner(ownerId)
                                        : Mono.empty();

                                return resetDefault
                                        .then(templateRepository.save(template))
                                        .onErrorMap(DuplicateKeyException.class, e ->
                                                new DuplicateResourceException("A template with this URL alias already exists"))
                                        .doOnSuccess(t -> log.info("Template created: {} (owner: {})", t.getSlug(), ownerId))
                                        .flatMap(this::mapToResponse);
                            });
                });
    }

    /**
     * Get template by ID.
     */
    public Mono<ResumeTemplateResponse> getTemplateById(Long id) {
        return templateRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Template not found: " + id)))
                .flatMap(this::mapToResponse);
    }

    /**
     * Get template by slug.
     */
    public Mono<ResumeTemplateResponse> getTemplateBySlug(String slug) {
        return templateRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Template not found: " + slug)))
                .flatMap(this::mapToResponse);
    }

    /**
     * Update an existing template.
     */
    @Transactional
    public Mono<ResumeTemplateResponse> updateTemplate(Long id, Long ownerId, ResumeTemplateRequest request) {
        return templateRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Template not found: " + id)))
                .flatMap(template -> {
                    // Verify ownership
                    if (!template.getOwnerId().equals(ownerId)) {
                        return Mono.error(new IllegalArgumentException("You don't have permission to update this template"));
                    }

                    // Update fields
                    if (request.getName() != null) {
                        LocalizedText currentName = template.getName();
                        if (currentName != null) {
                            currentName.put("en", request.getName());
                        } else {
                            template.setName(LocalizedText.ofEnglish(request.getName()));
                        }
                    }
                    if (request.getDescription() != null) {
                        LocalizedText currentDesc = template.getDescription();
                        if (currentDesc != null) {
                            currentDesc.put("en", request.getDescription());
                        } else {
                            template.setDescription(LocalizedText.ofEnglish(request.getDescription()));
                        }
                    }
                    if (request.getHtmlContent() != null) {
                        template.setHtmlContent(request.getHtmlContent());
                    }
                    if (request.getCssContent() != null) {
                        template.setCssContent(request.getCssContent());
                    }
                    if (request.getStatus() != null) {
                        template.setStatus(request.getStatus());
                    }
                    if (request.getPaperSize() != null) {
                        template.setPaperSize(request.getPaperSize());
                    }
                    if (request.getOrientation() != null) {
                        template.setOrientation(request.getOrientation());
                    }
                    if (request.getAlias() != null) {
                        String alias = request.getAlias().isBlank() ? null
                                : ALIAS_CHARS.matcher(request.getAlias().toLowerCase()).replaceAll("");
                        template.setAlias(alias);
                    }

                    template.incrementVersion();

                    // Handle default flag change
                    Mono<Void> handleDefault = Mono.empty();
                    if (request.getIsDefault() != null) {
                        if (request.getIsDefault() && !template.getIsDefault()) {
                            // Setting as default: reset all others first
                            template.setIsDefault(true);
                            handleDefault = templateRepository.resetDefaultForOwner(ownerId);
                        } else {
                            // Unsetting default or no-change — just sync the flag
                            template.setIsDefault(request.getIsDefault());
                        }
                    }

                    // Use DatabaseClient for UPDATE to avoid R2DBC save() issues with H2
                    return handleDefault.then(Mono.defer(() -> {
                            var sqlSpec = databaseClient.sql(
                                "UPDATE resume_templates SET name = :name, description = :desc, " +
                                "html_content = :html, css_content = :css, status = :status, " +
                                "paper_size = :paperSize, orientation = :orientation, " +
                                "url_alias = :alias, " +
                                "version = :version, is_default = :isDefault, updated_at = :updatedAt " +
                                "WHERE id = :id")
                                .bind("name", jsonValue(template.getName().toJson()))
                                .bind("desc", template.getDescription() != null
                                        ? jsonValue(template.getDescription().toJson())
                                        : jsonValue("{}"))
                                .bind("html", template.getHtmlContent())
                                .bind("css", template.getCssContent() != null ? template.getCssContent() : "")
                                .bind("status", template.getStatus())
                                .bind("paperSize", template.getPaperSize())
                                .bind("orientation", template.getOrientation());

                            sqlSpec = template.getAlias() != null
                                    ? sqlSpec.bind("alias", template.getAlias())
                                    : sqlSpec.bindNull("alias", String.class);

                            return sqlSpec
                                .bind("version", template.getVersion())
                                .bind("isDefault", template.getIsDefault())
                                .bind("updatedAt", template.getUpdatedAt())
                                .bind("id", template.getId())
                                .fetch().rowsUpdated();
                    }))
                            .doOnSuccess(rows -> log.info("Template updated: {} (v{})", template.getSlug(), template.getVersion()))
                            .onErrorMap(DuplicateKeyException.class, e ->
                                    new DuplicateResourceException("A template with this URL alias already exists"))
                            .then(Mono.just(template))
                            .flatMap(this::mapToResponse);
                });
    }

    /**
     * Delete a template.
     */
    public Mono<Boolean> deleteTemplate(Long id, Long ownerId) {
        return templateRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Template not found: " + id)))
                .flatMap(template -> {
                    if (!template.getOwnerId().equals(ownerId)) {
                        return Mono.error(new IllegalArgumentException("You don't have permission to delete this template"));
                    }
                    return databaseClient.sql("DELETE FROM resume_templates WHERE id = :id")
                            .bind("id", id)
                            .fetch().rowsUpdated()
                            .doOnSuccess(rows -> log.info("Template deleted: {} (rows={})", template.getSlug(), rows))
                            .thenReturn(true);
                });
    }

    // ==================== List Operations ====================

    /**
     * Get all templates for an owner with pagination.
     * Pre-fetches owner name once to avoid N+1 user lookups.
     */
    public Mono<PageResponse<ResumeTemplateResponse>> getTemplatesByOwner(Long ownerId, int page, int size) {
        int offset = page * size;

        Mono<String> ownerNameMono = userRepository.findById(ownerId)
                .map(user -> user.getName() != null ? user.getName() : user.getEmail())
                .defaultIfEmpty("Unknown");

        return ownerNameMono.flatMap(ownerName ->
            Mono.zip(
                    templateRepository.findByOwnerIdPaginated(ownerId, size, offset)
                            .map(t -> mapToResponseWithOwner(t, ownerName))
                            .collectList(),
                    templateRepository.countByOwnerId(ownerId)
            ).map(tuple -> {
                int totalPages = (int) Math.ceil((double) tuple.getT2() / size);
                return PageResponse.<ResumeTemplateResponse>builder()
                        .content(tuple.getT1())
                        .page(page)
                        .size(size)
                        .totalElements(tuple.getT2())
                        .totalPages(totalPages)
                        .first(page == 0)
                        .last(page >= totalPages - 1)
                        .build();
            })
        );
    }

    /**
     * Get templates by status.
     */
    public Flux<ResumeTemplateResponse> getTemplatesByOwnerAndStatus(Long ownerId, String status) {
        return templateRepository.findByOwnerIdAndStatus(ownerId, status)
                .flatMap(this::mapToResponse);
    }

    /**
     * Search templates by name.
     */
    public Flux<ResumeTemplateResponse> searchTemplates(Long ownerId, String searchTerm) {
        String sanitized = DigestUtils.escapeLikePattern(searchTerm);
        return templateRepository.searchByName(ownerId, sanitized)
                .flatMap(this::mapToResponse);
    }

    /**
     * Get most popular templates.
     */
    public Flux<ResumeTemplateResponse> getMostDownloaded(int limit) {
        return templateRepository.findMostDownloaded(limit)
                .flatMap(this::mapToResponse);
    }

    // ==================== PDF Generation ====================

    /**
     * Generate PDF from a saved template.
     */
    public Mono<byte[]> generatePdfFromTemplate(Long templateId, Map<String, String> variables) {
        return templateRepository.findById(templateId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Template not found: " + templateId)))
                .flatMap(template -> {
                    boolean landscape = "LANDSCAPE".equals(template.getOrientation());
                    
                    // Increment download count
                    return templateRepository.incrementDownloadCount(templateId)
                            .then(pdfGenerationService.generatePdfWithVariables(
                                    template.getHtmlContent(),
                                    variables,
                                    template.getPaperSize(),
                                    landscape
                            ))
                            .doOnSuccess(bytes -> log.info("PDF generated from template: {} ({} bytes)", 
                                    template.getSlug(), bytes.length));
                });
    }

    /**
     * Generate PDF from a template slug.
     */
    public Mono<byte[]> generatePdfFromSlug(String slug, Map<String, String> variables) {
        return templateRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Template not found: " + slug)))
                .flatMap(template -> generatePdfFromTemplate(template.getId(), variables));
    }

    /**
     * Generate PDF from raw HTML content.
     */
    public Mono<byte[]> generatePdfFromHtml(PdfGenerationRequest request) {
        String paperSize = request.getPaperSize() != null ? request.getPaperSize() : "A4";
        boolean landscape = "LANDSCAPE".equals(request.getOrientation());

        if (request.getHtmlContent() != null) {
            return pdfGenerationService.generatePdfWithVariables(
                    request.getHtmlContent(),
                    request.getVariables(),
                    paperSize,
                    landscape
            );
        } else if (request.getTemplateId() != null) {
            return generatePdfFromTemplate(request.getTemplateId(), request.getVariables());
        } else if (request.getTemplateSlug() != null) {
            return generatePdfFromSlug(request.getTemplateSlug(), request.getVariables());
        } else {
            return Mono.error(new IllegalArgumentException("Either htmlContent, templateId, or templateSlug is required"));
        }
    }

    /**
     * Get default template for an owner.
     */
    public Mono<ResumeTemplateResponse> getDefaultTemplate(Long ownerId) {
        return templateRepository.findByOwnerIdAndIsDefaultTrue(ownerId)
                .flatMap(this::mapToResponse);
    }

    // ==================== Helper Methods ====================

    /**
     * Returns the appropriate value for JSON/JSONB columns:
     * PostgreSQL uses Json.of(), H2 uses plain String.
     */
    private Object jsonValue(String json) {
        if (java.util.Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
            return json;
        }
        return Json.of(json);
    }

    private Mono<Boolean> validateHtmlContent(String htmlContent) {
        if (htmlContent == null || htmlContent.isBlank()) {
            return Mono.just(false);
        }
        return pdfGenerationService.validateHtml(htmlContent);
    }

    private String generateSlug(String name) {
        String slug = NON_SLUG_ALPHA.matcher(name.toLowerCase()).replaceAll("");
        slug = WHITESPACE.matcher(slug).replaceAll("-");
        slug = MULTI_HYPHEN.matcher(slug).replaceAll("-");
        return LEADING_TRAILING_HYPHENS.matcher(slug).replaceAll("");
    }

    private Mono<String> ensureUniqueSlug(String baseSlug) {
        return templateRepository.existsBySlug(baseSlug)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.just(baseSlug);
                    }
                    // Use UUID suffix for guaranteed uniqueness (avoids millisecond collisions)
                    String uniqueSlug = baseSlug + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
                    return Mono.just(uniqueSlug);
                });
    }

    private Mono<ResumeTemplateResponse> mapToResponse(ResumeTemplate template) {
        return userRepository.findById(template.getOwnerId())
                .map(user -> user.getName() != null ? user.getName() : user.getEmail())
                .defaultIfEmpty("Unknown")
                .map(ownerName -> mapToResponseWithOwner(template, ownerName));
    }

    private ResumeTemplateResponse mapToResponseWithOwner(ResumeTemplate template, String ownerName) {
        return ResumeTemplateResponse.builder()
                .id(String.valueOf(template.getId()))
                .slug(template.getSlug())
                .alias(template.getAlias())
                .name(template.getName() != null ? template.getName().getDefault() : null)
                .description(template.getDescription() != null ? template.getDescription().getDefault() : null)
                .names(template.getName() != null ? template.getName().getTranslations() : null)
                .descriptions(template.getDescription() != null ? template.getDescription().getTranslations() : null)
                .htmlContent(template.getHtmlContent())
                .cssContent(template.getCssContent())
                .status(template.getStatus())
                .ownerId(String.valueOf(template.getOwnerId()))
                .ownerName(ownerName)
                .version(template.getVersion())
                .isDefault(template.getIsDefault())
                .paperSize(template.getPaperSize())
                .orientation(template.getOrientation())
                .downloadCount(template.getDownloadCount())
                .previewUrl(template.getPreviewUrl())
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
    }
}
