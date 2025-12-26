package dev.catananti.service;

import dev.catananti.dto.ArticleVersionResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleVersion;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.ArticleVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Service for managing article version history.
 * Automatically creates versions when articles are updated.
 * TODO F-185: Add version pruning — limit stored versions per article (e.g., keep last 50)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleVersionService {

    private final ArticleVersionRepository versionRepository;
    private final ArticleRepository articleRepository;
    private final IdService idService;

    /**
     * Create a new version of an article.
     * Call this before updating an article to preserve its current state.
     */
    @Transactional
    public Mono<ArticleVersion> createVersion(Article article, String changeSummary, Long changedBy, String changedByName) {
        return versionRepository.findMaxVersionNumber(article.getId())
                .defaultIfEmpty(0)
                .flatMap(maxVersion -> {
                    ArticleVersion version = ArticleVersion.builder()
                            .id(idService.nextId())
                            .articleId(article.getId())
                            .versionNumber(maxVersion + 1)
                            .title(article.getTitle())
                            .subtitle(article.getSubtitle())
                            .content(article.getContent())
                            .excerpt(article.getExcerpt())
                            .coverImageUrl(article.getCoverImageUrl())
                            .changeSummary(changeSummary)
                            .changedBy(changedBy)
                            .changedByName(changedByName)
                            .createdAt(LocalDateTime.now())
                            .build();

                    return versionRepository.save(version)
                            .doOnSuccess(v -> log.info("Created version {} for article {}", v.getVersionNumber(), article.getSlug()));
                });
    }

    /**
     * Get all versions of an article.
     */
    public Flux<ArticleVersionResponse> getVersionHistory(Long articleId) {
        return versionRepository.findByArticleIdOrderByVersionNumberDesc(articleId)
                .map(ArticleVersionResponse::fromEntity);
    }

    /**
     * Get a specific version of an article.
     */
    public Mono<ArticleVersionResponse> getVersion(Long articleId, Integer versionNumber) {
        return versionRepository.findByArticleIdAndVersionNumber(articleId, versionNumber)
                .map(ArticleVersionResponse::fromEntity)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(
                        "Article version not found: article=" + articleId + ", version=" + versionNumber)));
    }

    /**
     * Get the latest version of an article.
     */
    public Mono<ArticleVersionResponse> getLatestVersion(Long articleId) {
        return versionRepository.findLatestByArticleId(articleId)
                .map(ArticleVersionResponse::fromEntity)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(
                        "No versions found for article: " + articleId)));
    }

    /**
     * Restore an article to a previous version.
     */
    @Transactional
    public Mono<Article> restoreVersion(Long articleId, Integer versionNumber, Long restoredBy, String restoredByName) {
        return articleRepository.findById(articleId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article not found: " + articleId)))
                .flatMap(article -> 
                    versionRepository.findByArticleIdAndVersionNumber(articleId, versionNumber)
                            .switchIfEmpty(Mono.error(new ResourceNotFoundException(
                                    "Version not found: " + versionNumber)))
                            .flatMap(version -> {
                                // First, create a version of the current state
                                return createVersion(article, "Before restoring to version " + versionNumber, 
                                        restoredBy, restoredByName)
                                        .then(Mono.defer(() -> {
                                            // Restore the old version's content
                                            article.setTitle(version.getTitle());
                                            article.setSubtitle(version.getSubtitle());
                                            article.setContent(version.getContent());
                                            article.setExcerpt(version.getExcerpt());
                                            article.setCoverImageUrl(version.getCoverImageUrl());
                                            article.setUpdatedAt(LocalDateTime.now());
                                            
                                            return articleRepository.save(article)
                                                    .doOnSuccess(a -> log.info("Restored article {} to version {}", 
                                                            a.getSlug(), versionNumber));
                                        }));
                            })
                );
    }

    /**
     * Get version count for an article.
     */
    public Mono<Long> getVersionCount(Long articleId) {
        return versionRepository.countByArticleId(articleId);
    }

    /**
     * Compare two versions of an article.
     * Returns a diff-like summary of changes.
     */
    public Mono<VersionDiff> compareVersions(Long articleId, Integer fromVersion, Integer toVersion) {
        return Mono.zip(
                versionRepository.findByArticleIdAndVersionNumber(articleId, fromVersion)
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException("Version not found: " + fromVersion))),
                versionRepository.findByArticleIdAndVersionNumber(articleId, toVersion)
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException("Version not found: " + toVersion)))
        ).map(tuple -> {
            ArticleVersion from = tuple.getT1();
            ArticleVersion to = tuple.getT2();
            
            return new VersionDiff(
                    articleId,
                    fromVersion,
                    toVersion,
                    !java.util.Objects.equals(from.getTitle(), to.getTitle()),
                    !java.util.Objects.equals(from.getSubtitle(), to.getSubtitle()),
                    !java.util.Objects.equals(from.getContent(), to.getContent()),
                    !java.util.Objects.equals(from.getExcerpt(), to.getExcerpt()),
                    Math.abs(
                            (from.getContent() != null ? from.getContent().length() : 0) - 
                            (to.getContent() != null ? to.getContent().length() : 0)
                    ),
                    from.getCreatedAt(),
                    to.getCreatedAt()
            );
        });
    }

    /**
     * Delete all versions of an article.
     */
    @Transactional
    public Mono<Void> deleteVersionHistory(Long articleId) {
        return versionRepository.deleteByArticleId(articleId)
                .doOnSuccess(v -> log.info("Deleted version history for article: {}", articleId));
    }

    /**
     * Record representing differences between two versions.
     */
    public record VersionDiff(
            Long articleId,
            Integer fromVersion,
            Integer toVersion,
            boolean titleChanged,
            boolean subtitleChanged,
            boolean contentChanged,
            boolean excerptChanged,
            int contentLengthDiff,
            LocalDateTime fromDate,
            LocalDateTime toDate
    ) {}
}
