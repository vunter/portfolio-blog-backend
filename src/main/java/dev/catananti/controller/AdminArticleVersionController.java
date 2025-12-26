package dev.catananti.controller;

import dev.catananti.dto.ArticleVersionResponse;
import dev.catananti.repository.UserRepository;
import dev.catananti.service.ArticleVersionService;
import dev.catananti.service.ArticleVersionService.VersionDiff;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/articles/{articleId}/versions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'DEV', 'EDITOR')")
@Tag(name = "Admin - Article Versions", description = "Article version history management")
@SecurityRequirement(name = "Bearer Authentication")
@Slf4j
public class AdminArticleVersionController {

    private final ArticleVersionService versionService;
    // TODO F-114: Use ArticleVersionService instead of direct repository injection
    private final UserRepository userRepository;

    @GetMapping
    @Operation(summary = "Get version history", description = "Get all versions of an article")
    public Mono<ResponseEntity<Map<String, Object>>> getVersionHistory(
            @PathVariable Long articleId) {
        log.debug("Fetching version history for articleId={}", articleId);
        return versionService.getVersionHistory(articleId)
                .collectList()
                .zipWith(versionService.getVersionCount(articleId))
                .map(tuple -> {
                    List<ArticleVersionResponse> versions = tuple.getT1();
                    Long count = tuple.getT2();
                    return ResponseEntity.ok(Map.of(
                            "articleId", articleId,
                            "versions", versions,
                            "totalVersions", count
                    ));
                });
    }

    @GetMapping("/latest")
    @Operation(summary = "Get latest version", description = "Get the most recent version of an article")
    public Mono<ResponseEntity<ArticleVersionResponse>> getLatestVersion(
            @PathVariable Long articleId) {
        log.debug("Fetching latest version for articleId={}", articleId);
        return versionService.getLatestVersion(articleId)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{versionNumber}")
    @Operation(summary = "Get specific version", description = "Get a specific version of an article")
    public Mono<ResponseEntity<ArticleVersionResponse>> getVersion(
            @PathVariable Long articleId,
            @PathVariable Integer versionNumber) {
        log.debug("Fetching version {} for articleId={}", versionNumber, articleId);
        return versionService.getVersion(articleId, versionNumber)
                .map(ResponseEntity::ok);
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
        return userRepository.findByEmail(authentication.getName())
                .flatMap(user -> versionService.restoreVersion(articleId, versionNumber, user.getId(), user.getName()))
                .map(article -> ResponseEntity.ok(Map.of(
                        "message", "Article restored to version " + versionNumber,
                        "articleId", articleId,
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
            @RequestParam Integer toVersion) {
        log.debug("Comparing versions {} and {} for articleId={}", fromVersion, toVersion, articleId);
        return versionService.compareVersions(articleId, fromVersion, toVersion)
                .map(ResponseEntity::ok);
    }
}
