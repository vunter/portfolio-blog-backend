package dev.catananti.controller;

import dev.catananti.dto.ArticleVersionResponse;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.ArticleVersionService;
import dev.catananti.util.PiiMasker;
import dev.catananti.service.ArticleVersionService.VersionDiff;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/articles/{articleId}/versions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV')")
@Tag(name = "Admin - Article Versions", description = "Article version history management")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminArticleVersionController {

    private final ArticleVersionService versionService;
    private final UserRepository userRepository;
    private final ArticleRepository articleRepository;

    @GetMapping
    @Operation(summary = "Get version history", description = "Get all versions of an article")
    public Mono<ResponseEntity<Map<String, Object>>> getVersionHistory(
            @PathVariable Long articleId,
            Authentication authentication) {
        log.debug("Fetching version history for articleId={}", articleId);
        // AUD18-JM7: author-scoped — versions carry full article content drafts.
        // Mono.defer so no version query is even assembled before the access check passes.
        return requireArticleAccess(articleId, authentication)
                .then(Mono.defer(() -> versionService.getVersionHistory(articleId)
                        .collectList()
                        .zipWith(versionService.getVersionCount(articleId))
                        .map(tuple -> {
                            List<ArticleVersionResponse> versions = tuple.getT1();
                            Long count = tuple.getT2();
                            return ResponseEntity.ok(Map.of(
                                    // AUD19C-SNOW: stringify Snowflake id for JS clients
                                    "articleId", String.valueOf(articleId),
                                    "versions", versions,
                                    "totalVersions", count
                            ));
                        })));
    }

    @GetMapping("/{versionNumber}")
    @Operation(summary = "Get specific version", description = "Get a specific version of an article")
    public Mono<ResponseEntity<ArticleVersionResponse>> getVersion(
            @PathVariable Long articleId,
            @PathVariable Integer versionNumber,
            Authentication authentication) {
        log.debug("Fetching version {} for articleId={}", versionNumber, articleId);
        // AUD18-JM7: author-scoped
        return requireArticleAccess(articleId, authentication)
                .then(Mono.defer(() -> versionService.getVersion(articleId, versionNumber)
                        .map(ResponseEntity::ok)));
    }

    @PostMapping("/{versionNumber}/restore")
    @Operation(summary = "Restore version", description = "Restore an article to a previous version")
    public Mono<ResponseEntity<Map<String, Object>>> restoreVersion(
            @PathVariable Long articleId,
            @PathVariable Integer versionNumber,
            @Parameter(description = "Summary of why the version is being restored")
            @RequestParam(defaultValue = "Manual restore") String reason,
            Authentication authentication) {
        log.info("Restoring articleId={} to versionNumber={}", articleId, versionNumber);
        return userRepository.findByEmail(PiiMasker.extractEmail(authentication))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "error.user_not_found")))
                .flatMap(user -> articleRepository.findById(articleId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "error.article_not_found")))
                        .flatMap(article -> {
                            // Ownership check: only the author or an ADMIN may restore
                            boolean isAdmin = "ADMIN".equals(user.getRole());
                            boolean isAuthor = article.getAuthorId() != null
                                    && article.getAuthorId().equals(user.getId());
                            if (!isAdmin && !isAuthor) {
                                log.warn("User {} attempted to restore articleId={} without ownership",
                                        PiiMasker.maskEmail(user.getEmail()), articleId);
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                        "error.not_article_owner"));
                            }
                            return versionService.restoreVersion(articleId, versionNumber,
                                    user.getId(), user.getName());
                        }))
                .map(article -> ResponseEntity.ok(Map.of(
                        "message", "Article restored to version " + versionNumber,
                        // AUD19C-SNOW: stringify Snowflake id for JS clients
                        "articleId", String.valueOf(articleId),
                        "restoredVersion", versionNumber,
                        "currentSlug", article.getSlug()
                )));
    }

    @GetMapping("/compare")
    @Operation(summary = "Compare versions", description = "Compare two versions of an article")
    public Mono<ResponseEntity<VersionDiff>> compareVersions(
            @PathVariable Long articleId,
            @Parameter(description = "Version number to compare from")
            @RequestParam Integer fromVersion,
            @Parameter(description = "Version number to compare to")
            @RequestParam Integer toVersion,
            Authentication authentication) {
        log.debug("Comparing versions {} and {} for articleId={}", fromVersion, toVersion, articleId);
        // AUD18-JM7: author-scoped
        return requireArticleAccess(articleId, authentication)
                .then(Mono.defer(() -> versionService.compareVersions(articleId, fromVersion, toVersion)
                        .map(ResponseEntity::ok)));
    }

    /**
     * AUD18-JM7: shared author-scoping guard, mirroring the check restoreVersion already
     * had: ADMIN may access any article's versions; DEV only articles they authored.
     * 404 when the user or article is missing, 403 otherwise.
     */
    private Mono<Void> requireArticleAccess(Long articleId, Authentication authentication) {
        return userRepository.findByEmail(PiiMasker.extractEmail(authentication))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "error.user_not_found")))
                .flatMap(user -> articleRepository.findById(articleId)
                        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "error.article_not_found")))
                        .flatMap(article -> {
                            boolean isAdmin = "ADMIN".equals(user.getRole());
                            boolean isAuthor = article.getAuthorId() != null
                                    && article.getAuthorId().equals(user.getId());
                            if (!isAdmin && !isAuthor) {
                                log.warn("User {} attempted to access versions of articleId={} without ownership",
                                        PiiMasker.maskEmail(user.getEmail()), articleId);
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                        "error.not_article_owner"));
                            }
                            return Mono.empty();
                        }))
                .then();
    }
}
