package dev.catananti.controller;

import dev.catananti.config.LocaleConstants;
import dev.catananti.dto.ArticleI18nResponse;
import dev.catananti.dto.ArticleRequest;
import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.ArticleAdminService;
import dev.catananti.service.ArticleService;
import dev.catananti.service.ArticleTranslationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/articles")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV', 'EDITOR')")
@Validated
@Slf4j
public class AdminArticleController {

    private final ArticleAdminService articleAdminService;
    private final ArticleService articleService;
    private final ArticleTranslationService articleTranslationService;

    // CQ-03: Use centralized locale constants
    private static final Set<String> SUPPORTED_LOCALES = LocaleConstants.SUPPORTED_LOCALE_CODES;

    @GetMapping
    public Mono<PageResponse<ArticleResponse>> getAllArticles(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Pattern(regexp = "^(DRAFT|PUBLISHED|ARCHIVED|SCHEDULED)?$", message = "Invalid status") String status,
            @RequestParam(required = false) String search) {
        log.debug("Admin fetching articles: page={}, size={}, status={}", page, size, status);
        if (search != null && !search.isBlank()) {
            return articleService.searchArticles(search.trim(), page, size);
        }
        return articleAdminService.getAllArticles(page, size, status);
    }

    @GetMapping("/{id}")
    public Mono<ArticleResponse> getArticleById(@PathVariable Long id) {
        log.debug("Admin fetching article id={}", id);
        return articleAdminService.getArticleById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ArticleResponse> createArticle(@Valid @RequestBody ArticleRequest request) {
        log.info("Admin creating article: title='{}'", request.getTitle());
        return articleAdminService.createArticle(request);
    }

    @PutMapping("/{id}")
    public Mono<ArticleResponse> updateArticle(
            @PathVariable Long id,
            @Valid @RequestBody ArticleRequest request) {
        log.info("Admin updating article id={}", id);
        return articleAdminService.updateArticle(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteArticle(@PathVariable Long id) {
        log.info("Admin deleting article id={}", id);
        return articleAdminService.deleteArticle(id);
    }

    @PatchMapping("/{id}/publish")
    public Mono<ArticleResponse> publishArticle(@PathVariable Long id) {
        log.info("Admin publishing article id={}", id);
        return articleAdminService.publishArticle(id);
    }

    @PatchMapping("/{id}/unpublish")
    public Mono<ArticleResponse> unpublishArticle(@PathVariable Long id) {
        return articleAdminService.unpublishArticle(id);
    }

    @PatchMapping("/{id}/archive")
    public Mono<ArticleResponse> archiveArticle(@PathVariable Long id) {
        return articleAdminService.archiveArticle(id);
    }

    // ==================== TRANSLATION ENDPOINTS ====================

    @PostMapping("/{id}/translate")
    public Mono<ResponseEntity<ArticleI18nResponse>> translateArticle(
            @PathVariable Long id,
            @RequestParam String targetLang) {
        // VAL-03: Validate targetLang against supported locales
        if (!SUPPORTED_LOCALES.contains(targetLang.toLowerCase())) {
            return Mono.error(new IllegalArgumentException(
                    "Unsupported locale: " + targetLang + ". Supported: " + SUPPORTED_LOCALES));
        }
        return articleTranslationService.translateArticle(id, targetLang)
                .map(ArticleI18nResponse::from)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto));
    }

    @GetMapping("/{id}/translations")
    public Mono<List<ArticleI18nResponse>> getTranslations(@PathVariable Long id) {
        return articleTranslationService.getTranslations(id)
                .map(ArticleI18nResponse::from)
                .collectList();
    }

    @GetMapping("/{id}/translations/locales")
    public Mono<List<String>> getAvailableLocales(@PathVariable Long id) {
        return articleTranslationService.getAvailableLocales(id).collectList();
    }

    @GetMapping("/{id}/translations/{locale}")
    public Mono<ResponseEntity<ArticleI18nResponse>> getTranslation(
            @PathVariable Long id,
            @PathVariable String locale) {
        return articleTranslationService.getTranslation(id, locale)
                .map(ArticleI18nResponse::from)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/translations/{locale}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteTranslation(
            @PathVariable Long id,
            @PathVariable String locale) {
        return articleTranslationService.deleteTranslation(id, locale);
    }
}
