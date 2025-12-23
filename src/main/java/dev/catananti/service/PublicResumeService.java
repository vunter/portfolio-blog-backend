package dev.catananti.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.catananti.config.LocaleConstants;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeTemplate;
import dev.catananti.entity.User;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ResumeTemplateRepository;
import dev.catananti.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Service for generating public resumes by alias.
 * This service handles resume generation for public download without authentication.
 * Priority: 1) Template's stored HTML → 2) Profile data (translatable) → 3) 404
 */
// TODO F-202: Sanitize template HTML/CSS to prevent stored XSS in rendered resumes
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicResumeService {

    private final PdfGenerationService pdfGenerationService;
    private final ResumeTemplateRepository resumeTemplateRepository;
    private final ResumeProfileService resumeProfileService;
    private final UserRepository userRepository;
    
    // PDF cache with 5-minute TTL and max 50 entries (M7: replaced manual ConcurrentHashMap with Caffeine)
    private final Cache<String, byte[]> pdfCache = Caffeine.newBuilder()
            .maximumSize(50)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    // CQ-03: Use centralized locale constants
    private static final Set<String> SUPPORTED_LOCALES = LocaleConstants.SUPPORTED_LOCALE_CODES;
    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-z]{2}$");

    /**
     * Validate and normalize the locale parameter.
     * Preserves full locale codes (e.g., "pt-br") so the profile service can do exact matching.
     * Returns "en" for null/blank/invalid values.
     */
    private String validateLocale(String lang) {
        if (lang == null || lang.isBlank()) {
            return "en";
        }
        String normalized = lang.trim().toLowerCase();
        // Check full code first (e.g., "pt-br"), then prefix (e.g., "pt")
        if (SUPPORTED_LOCALES.contains(normalized)) {
            return normalized;
        }
        String prefix = normalized.split("[_-]")[0];
        if (LOCALE_PATTERN.matcher(prefix).matches() && SUPPORTED_LOCALES.contains(prefix)) {
            return prefix;
        }
        log.warn("Unsupported locale '{}', falling back to 'en'", lang);
        return "en";
    }

    /**
     * Generate PDF for a resume by alias with 5-minute cache.
     * Cache auto-invalidates after TTL. Profile changes take effect after cache expires.
     */
    public Mono<byte[]> generateResumePdf(String alias, String lang) {
        String validLang = validateLocale(lang);
        String cacheKey = alias.toLowerCase() + "-" + validLang;
        
        // Check cache first
        byte[] cached = pdfCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("PDF cache hit for: {}", cacheKey);
            return Mono.just(cached);
        }
        
        log.debug("PDF cache miss for: {}, generating...", cacheKey);
        
        return getResumeHtmlContent(alias, validLang)
                .flatMap(html -> pdfGenerationService.generatePdf(html, "A4", false))
                .doOnSuccess(pdfBytes -> {
                    // Cache the result
                    pdfCache.put(cacheKey, pdfBytes);
                    log.info("PDF cached for: {} (expires in 5 minutes)", cacheKey);
                })
                .doOnError(e -> log.error("PDF generation failed for alias: {}", alias, e));
    }

    /**
     * Get resume HTML by alias.
     * Priority: 1) Template's stored HTML → 2) Profile data (translatable) → 3) 404
     */
    public Mono<String> getResumeHtml(String alias, String lang) {
        return getResumeHtmlContent(alias, validateLocale(lang));
    }
    
    /**
     * Get public resume profile JSON by alias.
     * Resolves alias → template → ownerId → profile data with locale fallback.
     */
    public Mono<ResumeProfileResponse> getProfileByAlias(String alias, String lang) {
        String validLang = validateLocale(lang);
        String normalizedAlias = alias.toLowerCase();
        return resolveTemplate(normalizedAlias)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Resume not found for alias: " + alias)))
                .flatMap(template -> {
                    Long ownerId = template.getOwnerId();
                    if (ownerId == null) {
                        return Mono.error(new ResourceNotFoundException("Resume template has no owner: " + alias));
                    }
                    return resumeProfileService.getProfileByOwnerIdWithFallback(ownerId, validLang);
                });
    }

    /**
     * Clear the PDF cache for a specific alias or all.
     * Call this after profile updates to ensure fresh PDF generation.
     */
    public void clearPdfCache(String alias) {
        if (alias == null) {
            pdfCache.invalidateAll();
            log.info("PDF cache cleared for all aliases");
        } else {
            pdfCache.asMap().keySet().removeIf(k -> k.startsWith(alias.toLowerCase()));
            log.info("PDF cache cleared for alias: {}", alias);
        }
    }

    /**
     * Get all published developer profiles (active templates with aliases).
     * Returns lightweight summaries with alias, name, title, and avatar.
     * Used by the public profile selector component.
     */
    public Flux<dev.catananti.dto.PublicProfileSummary> getPublishedProfiles(String lang) {
        String validLang = validateLocale(lang);
        return resumeTemplateRepository.findActiveWithAlias()
                .flatMap(template -> {
                    Long ownerId = template.getOwnerId();
                    if (ownerId == null) {
                        return Mono.empty();
                    }
                    // Fetch user info (name, avatar) and profile title in parallel
                    Mono<User> userMono = userRepository.findById(ownerId).defaultIfEmpty(User.builder().name("Unknown").build());
                    Mono<ResumeProfileResponse> profileMono = resumeProfileService
                            .getProfileByOwnerIdWithFallback(ownerId, validLang)
                            .onErrorResume(e -> Mono.empty())
                            .defaultIfEmpty(ResumeProfileResponse.builder().build());

                    return Mono.zip(userMono, profileMono)
                            .map(tuple -> {
                                User user = tuple.getT1();
                                ResumeProfileResponse profile = tuple.getT2();
                                return dev.catananti.dto.PublicProfileSummary.builder()
                                        .alias(template.getAlias())
                                        .name(profile.getFullName() != null ? profile.getFullName() : user.getName())
                                        .title(profile.getTitle())
                                        .avatarUrl(user.getAvatarUrl())
                                        .build();
                            });
                });
    }

    /**
     * Get the resume HTML content using alias-based lookup.
     * Resolves the template by alias or slug, then uses its stored HTML.
     * 
     * Priority:
     * 1) Find template by alias (or slug) → use template's stored HTML content
     * 2) Fall back to profile-based generation (supports translation)
     * 3) 404 if nothing found
     */
    private Mono<String> getResumeHtmlContent(String alias, String lang) {
        String normalizedAlias = alias.toLowerCase();
        
        return resolveTemplate(normalizedAlias)
                .flatMap(template -> {
                    String templateHtml = template.getHtmlContent();
                    // Use the template's stored HTML if it contains real content
                    if (templateHtml != null && !templateHtml.isBlank() && templateHtml.length() > 200) {
                        log.info("Using template HTML for alias: {} (template: {}, {} bytes)", 
                                normalizedAlias, template.getId(), templateHtml.length());
                        return Mono.just(buildFullHtml(template, lang));
                    }
                    // Template has no real HTML content, try profile-based generation
                    Long ownerId = template.getOwnerId();
                    if (ownerId == null) {
                        log.warn("Template '{}' has no HTML content and no owner", template.getSlug());
                        return Mono.empty();
                    }
                    return resumeProfileService.generateResumeHtml(ownerId, lang)
                            .doOnSuccess(html -> log.info("Resume generated from profile data for alias: {} (owner: {}, lang: {})", 
                                    normalizedAlias, ownerId, lang))
                            .onErrorResume(e -> {
                                log.debug("Profile generation failed for alias {}: {}", normalizedAlias, e.getMessage());
                                return Mono.empty();
                            });
                })
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Resume not found for alias: " + alias)));
    }

    /**
     * Resolve a template by alias first, then by slug.
     * This allows templates to be found by their custom alias URL or auto-generated slug.
     */
    private Mono<ResumeTemplate> resolveTemplate(String aliasOrSlug) {
        return resumeTemplateRepository.findByAlias(aliasOrSlug)
                .filter(t -> "ACTIVE".equals(t.getStatus()))
                .switchIfEmpty(Mono.defer(() -> 
                    resumeTemplateRepository.findBySlug(aliasOrSlug)
                            .filter(t -> "ACTIVE".equals(t.getStatus()))
                ));
    }
    
    /**
     * Build full HTML document from a ResumeTemplate entity.
     * If the template already contains a complete HTML document (with DOCTYPE/html tags),
     * return it as-is. Otherwise, wrap it in a basic HTML structure.
     */
    private String buildFullHtml(ResumeTemplate template, String lang) {
        String html = template.getHtmlContent();
        
        // If the HTML content is already a complete document, return it directly
        if (html != null && html.trim().toLowerCase().startsWith("<!doctype")) {
            log.debug("Template HTML is a complete document, returning as-is");
            return html;
        }
        
        // Otherwise, wrap in a basic HTML structure
        String css = template.getCssContent() != null ? template.getCssContent() : "";
        // Sanitize CSS to prevent </style> breakout
        String safeCss = css.replace("</style", "&lt;/style");
        String safeName = (template.getName() != null ? template.getName().getDefault() : "Resume")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        
        return """
            <!DOCTYPE html>
            <html lang="%s">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s - Resume</title>
                <style>%s</style>
            </head>
            <body>
                %s
            </body>
            </html>
            """.formatted(lang, safeName, safeCss, html);
    }
    
}
