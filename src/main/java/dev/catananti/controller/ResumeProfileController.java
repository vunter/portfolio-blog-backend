package dev.catananti.controller;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.service.PdfGenerationService;
import dev.catananti.service.ProfileTranslationService;
import dev.catananti.service.PublicResumeService;
import dev.catananti.service.ResumeProfileService;
import dev.catananti.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * REST Controller for managing resume profile data (experiences, education, skills, etc.)
 * and generating HTML resumes from the stored profile.
 * TODO F-095: Add PATCH endpoint for partial profile updates instead of requiring full PUT
 */
@RestController
@RequestMapping("/api/v1/resume/profile")
@PreAuthorize("isAuthenticated()") // F-091: Enforce authentication at class level
@RequiredArgsConstructor
@Slf4j
@Validated
public class ResumeProfileController {

    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LEADING_TRAILING_HYPHENS = Pattern.compile("^-|-$");

    private final ResumeProfileService profileService;
    private final PdfGenerationService pdfGenerationService;
    private final PublicResumeService publicResumeService;
    private final ProfileTranslationService profileTranslationService;
    private final UserService userService;

    /**
     * Get the authenticated user's resume profile with all sections.
     * Returns an empty default profile if no profile exists for the given locale.
     * @param locale the language/locale of the profile (e.g., "en", "pt-br"). Defaults to "en".
     */
    @GetMapping
    public Mono<ResumeProfileResponse> getProfile(
            Authentication authentication,
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2})?$", message = "Invalid locale format") String locale) {
        return extractUserId(authentication)
                .flatMap(userId -> profileService.getProfileByOwnerId(userId, locale)
                        .switchIfEmpty(Mono.just(ResumeProfileResponse.builder()
                                .locale(locale)
                                .educations(java.util.List.of())
                                .experiences(java.util.List.of())
                                .skills(java.util.List.of())
                                .languages(java.util.List.of())
                                .certifications(java.util.List.of())
                                .additionalInfo(java.util.List.of())
                                .testimonials(java.util.List.of())
                                .proficiencies(java.util.List.of())
                                .projects(java.util.List.of())
                                .learningTopics(java.util.List.of())
                                .build())));
    }

    /**
     * Check if the authenticated user has a resume profile in any locale.
     */
    @GetMapping("/exists")
    public Mono<ResponseEntity<Boolean>> profileExists(Authentication authentication) {
        return extractUserId(authentication)
                .flatMap(profileService::profileExists)
                .map(ResponseEntity::ok);
    }

    /**
     * List all available locales for the authenticated user's profile.
     */
    @GetMapping("/locales")
    public Mono<ResponseEntity<java.util.List<String>>> listLocales(Authentication authentication) {
        return extractUserId(authentication)
                .flatMap(profileService::listProfileLocales)
                .map(ResponseEntity::ok);
    }

    /**
     * Create or update the authenticated user's resume profile for a specific locale.
     * Replaces all sections (education, experience, skills, etc.) atomically.
     * @param locale the language/locale to save this profile as (e.g., "en", "pt-br"). Defaults to "en".
     */
    @PutMapping
    public Mono<ResponseEntity<ResumeProfileResponse>> saveProfile(
            Authentication authentication,
            @Valid @RequestBody ResumeProfileRequest request,
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2})?$", message = "Invalid locale format") String locale) {
        return extractUserId(authentication)
                .flatMap(userId -> profileService.saveProfile(userId, request, locale))
                .doOnSuccess(profile -> {
                    // Invalidate public PDF cache so next download reflects updated profile
                    publicResumeService.clearPdfCache(null);
                })
                .map(ResponseEntity::ok);
    }

    /**
     * Generate an HTML resume from the user's stored profile data,
     * following the standard resume template structure.
     * @param locale language/locale for section headers ("en" or "pt"). Defaults to "en".
     */
    @GetMapping(value = "/generate-html", produces = "text/html;charset=UTF-8")
    public Mono<ResponseEntity<String>> generateHtml(
            Authentication authentication,
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2})?$", message = "Invalid locale format") String locale) {
        return extractUserId(authentication)
                .flatMap(userId -> profileService.generateResumeHtml(userId, locale))
                .map(html -> ResponseEntity.ok()
                        .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                        .body(html));
    }

    /**
     * Generate an HTML resume and return it as a downloadable file.
     * @param locale language/locale for the resume (e.g., "en", "pt", "pt-br"). Defaults to "en".
     */
    @GetMapping(value = "/download-html", produces = "text/html;charset=UTF-8")
    public Mono<ResponseEntity<String>> downloadHtml(
            Authentication authentication,
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2})?$", message = "Invalid locale format") String locale) {
        return extractUserId(authentication)
                .flatMap(userId -> profileService.getProfileByOwnerIdWithFallback(userId, locale)
                        .flatMap(profile -> profileService.generateResumeHtml(userId, locale)
                                .map(html -> {
                                    String filename = generateResumeFilename(profile.getFullName(), locale, "html");
                                    return ResponseEntity.ok()
                                            .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                                    "attachment; filename=\"" + filename + "\"")
                                            .body(html);
                                })));
    }

    /**
     * Generate a PDF resume from the user's stored profile data and return it as a downloadable file.
     * @param locale language/locale for the resume (e.g., "en", "pt", "pt-br"). Defaults to "en".
     */
    @GetMapping(value = "/download-pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<byte[]>> downloadPdf(
            Authentication authentication,
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "^[a-z]{2}(-[a-zA-Z]{2})?$", message = "Invalid locale format") String locale) {
        return extractUserId(authentication)
                .flatMap(userId -> profileService.getProfileByOwnerIdWithFallback(userId, locale)
                        .flatMap(profile -> profileService.generateResumeHtml(userId, locale)
                                .flatMap(html -> pdfGenerationService.generatePdf(html, "A4", false)
                                        .map(pdfBytes -> {
                                            String filename = generateResumeFilename(profile.getFullName(), locale, "pdf");
                                            return ResponseEntity.ok()
                                                    .header(HttpHeaders.CONTENT_DISPOSITION,
                                                            "attachment; filename=\"" + filename + "\"")
                                                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                                                    .header(HttpHeaders.CONTENT_LENGTH,
                                                            String.valueOf(pdfBytes.length))
                                                    .body(pdfBytes);
                                        }))));
    }

    /**
     * Translate the authenticated user's profile to the target language using DeepL API.
     * Loads the source profile from the sourceLang locale (default "en"), translates it,
     * and returns the translated content WITHOUT saving — the user reviews and saves manually.
     *
     * @param targetLang target language (e.g., "EN", "PT-BR", "DE", "FR", "ES")
     * @param sourceLang source language locale to translate from (defaults to "en")
     */
    @PostMapping("/translate")
    public Mono<ResponseEntity<ResumeProfileResponse>> translateProfile(
            Authentication authentication,
            @RequestParam(defaultValue = "EN") String targetLang,
            @RequestParam(defaultValue = "en") String sourceLang) {
        if (!profileTranslationService.isAvailable()) {
            return Mono.just(ResponseEntity.status(503).<ResumeProfileResponse>build());
        }
        return extractUserId(authentication)
                .flatMap(userId -> profileService.getProfileByOwnerIdWithFallback(userId, sourceLang)
                        .flatMap(profile -> profileTranslationService.translateProfile(profile, targetLang)))
                .map(ResponseEntity::ok);
    }

    /**
     * Check if the translation service is configured and available.
     */
    @GetMapping("/translate/status")
    public Mono<ResponseEntity<java.util.Map<String, Object>>> translationStatus() {
        boolean available = profileTranslationService.isAvailable();
        return Mono.just(ResponseEntity.ok(java.util.Map.of(
                "available", available,
                "provider", "DeepL",
                "supportedLanguages", java.util.List.of("EN", "PT-BR", "PT-PT", "DE", "FR", "ES", "IT", "NL", "PL", "RU", "JA", "ZH")
        )));
    }

    // ==================== Helper ====================

    /**
     * F-094: Extracted shared filename generation for downloadHtml and downloadPdf.
     * Slugifies the full name and appends locale suffix and extension.
     */
    private String generateResumeFilename(String fullName, String locale, String extension) {
        String safeName = fullName != null
                ? LEADING_TRAILING_HYPHENS.matcher(
                        NON_SLUG_CHARS.matcher(fullName.toLowerCase()).replaceAll("-")
                  ).replaceAll("")
                : "resume";
        String localeSuffix = locale != null && locale.toLowerCase().startsWith("pt") ? "-pt" : "";
        return "resume-" + safeName + localeSuffix + "." + extension;
    }

    // TODO F-093: Cache userId by email in Caffeine to avoid DB lookup per request
    private Mono<Long> extractUserId(Authentication authentication) {
        if (authentication == null) {
            return Mono.error(new IllegalStateException("User not authenticated"));
        }
        String email = authentication.getName();
        return userService.getUserByEmail(email)
                .map(user -> Long.valueOf(user.getId()));
    }
}
