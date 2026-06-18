package dev.catananti.service;

import dev.catananti.dto.ArticleRequest;
import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleReview;
import dev.catananti.entity.ArticleReviewStatus;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.Tag;
import dev.catananti.entity.User;
import dev.catananti.entity.UserRole;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.config.PaginationConfig;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.ArticleReviewRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.util.DigestUtils;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for admin-facing article operations: CRUD, publish/unpublish.
 * Public-facing read operations remain in {@link ArticleService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleAdminService {

    private static final int WORDS_PER_MINUTE = 200;

    private final ArticleRepository articleRepository;
    private final ArticleReviewRepository articleReviewRepository;
    private final TagRepository tagRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final SubscriberRepository subscriberRepository;
    private final EmailService emailService;
    private final ArticleVersionService articleVersionService;
    private final CurrentUserService currentUserService;
    private final CacheService cacheService;
    private final IdService idService;
    private final NotificationEventService notificationEventService;
    private final HtmlSanitizerService htmlSanitizerService;
    private final ArticleService articleService;
    private final PaginationConfig paginationConfig;
    private final TransactionalOperator transactionalOperator;

    private static final String RSS_CACHE_KEY = "rss:feed";
    private static final String SITEMAP_CACHE_KEY = "sitemap:xml";

    // ==================== ADMIN CRUD ====================

    public Mono<PageResponse<ArticleResponse>> getAllArticles(int page, int size, String status, String sort) {
        int offset = page * size;

        return getCurrentUser().flatMap(user -> {
            Flux<Article> articlesFlux;
            Mono<Long> countMono;

            if (isAdmin(user)) {
                // ADMIN sees all articles
                if (status != null && !status.isEmpty()) {
                    String s = status.toUpperCase();
                    articlesFlux = findByStatusSorted(s, sort, size, offset);
                    countMono = articleRepository.countByStatus(s);
                } else {
                    articlesFlux = findAllSorted(sort, size, offset);
                    countMono = articleRepository.countAll();
                }
            } else {
                // DEV see only their own articles (sort not supported for author-scoped queries)
                if (status != null && !status.isEmpty()) {
                    articlesFlux = articleRepository.findByAuthorIdAndStatusOrderByCreatedAtDesc(user.getId(), status.toUpperCase(), size, offset);
                    countMono = articleRepository.countByAuthorIdAndStatus(user.getId(), status.toUpperCase());
                } else {
                    articlesFlux = articleRepository.findByAuthorIdOrderByCreatedAtDesc(user.getId(), size, offset);
                    countMono = articleRepository.countByAuthorId(user.getId());
                }
            }

            return articlesFlux
                    .collectList()
                    .flatMap(articleService::enrichArticlesWithMetadata)
                    .zipWith(countMono)
                    .map(tuple -> {
                        var content = tuple.getT1().stream().map(articleService::mapToResponse).toList();
                        var total = tuple.getT2();
                        return PageResponse.of(content, page, size, total);
                    });
        });
    }

    /**
     * Admin-facing search across the caller's articles. Unlike the public
     * {@link ArticleService#searchArticles} (PUBLISHED-only), this spans ALL statuses so an
     * author can find DRAFT/SCHEDULED/REVIEW/ARCHIVED articles. DEV users are scoped to their
     * own articles; ADMIN sees everything.
     */
    public Mono<PageResponse<ArticleResponse>> searchArticles(String query, int page, int size) {
        int offset = page * size;
        // F-291: sanitize LIKE wildcards to prevent pattern injection.
        String sanitizedQuery = DigestUtils.escapeLikePattern(query);

        return getCurrentUser().flatMap(user -> {
            Flux<Article> articlesFlux;
            Mono<Long> countMono;
            if (isAdmin(user)) {
                articlesFlux = articleRepository.adminSearchByQuery(sanitizedQuery, size, offset);
                countMono = articleRepository.countAdminSearchByQuery(sanitizedQuery);
            } else {
                articlesFlux = articleRepository.adminSearchByAuthorAndQuery(user.getId(), sanitizedQuery, size, offset);
                countMono = articleRepository.countAdminSearchByAuthorAndQuery(user.getId(), sanitizedQuery);
            }
            return articlesFlux
                    .collectList()
                    .flatMap(articleService::enrichArticlesWithMetadata)
                    .zipWith(countMono)
                    .map(tuple -> {
                        var content = tuple.getT1().stream().map(articleService::mapToResponse).toList();
                        return PageResponse.of(content, page, size, tuple.getT2());
                    });
        });
    }

    private Flux<Article> findByStatusSorted(String status, String sort, int limit, int offset) {
        return switch (sort) {
            case "oldest" -> articleRepository.findByStatusOrderByCreatedAtAsc(status, limit, offset);
            case "title" -> articleRepository.findByStatusOrderByTitleAsc(status, limit, offset);
            case "views" -> articleRepository.findByStatusOrderByViewsDesc(status, limit, offset);
            case "likes" -> articleRepository.findByStatusOrderByLikesDesc(status, limit, offset);
            default -> articleRepository.findByStatusOrderByCreatedAtDesc(status, limit, offset);
        };
    }

    private Flux<Article> findAllSorted(String sort, int limit, int offset) {
        return switch (sort) {
            case "oldest" -> articleRepository.findAllOrderByCreatedAtAsc(limit, offset);
            case "title" -> articleRepository.findAllOrderByTitleAsc(limit, offset);
            case "views" -> articleRepository.findAllOrderByViewsDesc(limit, offset);
            case "likes" -> articleRepository.findAllOrderByLikesDesc(limit, offset);
            default -> articleRepository.findAllOrderByCreatedAtDesc(limit, offset);
        };
    }

    public Mono<ArticleResponse> getArticleById(Long id) {
        return articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> getCurrentUser().flatMap(user -> {
                    if (!isAdmin(user) && !isOwner(article, user)) {
                        return Mono.error(new AccessDeniedException("You can only view your own articles"));
                    }
                    return Mono.just(article);
                }))
                .flatMap(articleService::enrichArticleWithMetadata)
                .map(articleService::mapToResponse);
    }

    public Mono<ArticleResponse> publishArticle(Long id) {
        // Wrap only the DB writes in a transaction so the connection is released before
        // the subscriber email fan-out (which can be thousands of sends with concurrency=10).
        Mono<Article> publish = articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> verifyOwnership(article).thenReturn(article))
                .flatMap(article -> {
                    article.setStatus(ArticleStatus.PUBLISHED);
                    article.setPublishedAt(LocalDateTime.now());
                    article.setUpdatedAt(LocalDateTime.now());
                    return articleRepository.save(article);
                })
                .doOnSuccess(a -> {
                    log.info("Article published: {}", a.getSlug());
                    notificationEventService.articlePublished(a.getTitle(), a.getSlug());
                });
        return transactionalOperator.transactional(publish)
                .flatMap(article -> invalidateFeedCaches()
                        .then(notifySubscribersAboutNewArticle(article))
                        .thenReturn(article))
                .flatMap(articleService::enrichArticleWithMetadata)
                .map(articleService::mapToResponse);
    }

    @Transactional
    public Mono<ArticleResponse> unpublishArticle(Long id) {
        return articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> verifyOwnership(article).thenReturn(article))
                .flatMap(article -> {
                    article.setStatus(ArticleStatus.DRAFT);
                    article.setUpdatedAt(LocalDateTime.now());
                    return articleRepository.save(article);
                })
                .doOnSuccess(a -> log.info("Article unpublished: {}", a.getSlug()))
                .flatMap(article -> invalidateFeedCaches().thenReturn(article))
                .flatMap(articleService::enrichArticleWithMetadata)
                .map(articleService::mapToResponse);
    }

    @Transactional
    public Mono<ArticleResponse> archiveArticle(Long id) {
        return articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> verifyOwnership(article).thenReturn(article))
                .flatMap(article -> {
                    article.setStatus(ArticleStatus.ARCHIVED);
                    article.setUpdatedAt(LocalDateTime.now());
                    return articleRepository.save(article);
                })
                .doOnSuccess(a -> log.info("Article archived: {}", a.getSlug()))
                .flatMap(article -> invalidateFeedCaches().thenReturn(article))
                .flatMap(articleService::enrichArticleWithMetadata)
                .map(articleService::mapToResponse);
    }

    @Transactional
    public Mono<ArticleResponse> createArticle(ArticleRequest request) {
        // ARCH (LOW): flattened — the slug/author/tag resolution stages are chained
        // linearly and the build+persist step is extracted to buildAndSaveArticle.
        return resolveUniqueSlug(request.getSlug())
                .flatMap(uniqueSlug -> {
                    request.setSlug(uniqueSlug);
                    return getCurrentUser()
                            .map(User::getId)
                            .switchIfEmpty(Mono.error(new IllegalStateException("No authenticated user")));
                })
                .flatMap(authorId -> fetchOrCreateTags(request.getTagSlugs())
                        .collectList()
                        .flatMap(tags -> buildAndSaveArticle(request, authorId, tags)));
    }

    /**
     * Build the {@link Article} entity from the request, persist it together with its
     * tag mappings, fire notifications, and map to the response. Extracted from
     * {@link #createArticle(ArticleRequest)} to reduce nesting (ARCH).
     */
    private Mono<ArticleResponse> buildAndSaveArticle(ArticleRequest request, Long authorId, List<Tag> tags) {
        ArticleStatus status = ArticleStatus.fromString(request.getStatus(), ArticleStatus.DRAFT);

        if (request.getScheduledAt() != null && status != ArticleStatus.PUBLISHED) {
            status = ArticleStatus.SCHEDULED;
        }

        Article article = Article.builder()
                .id(idService.nextId())
                .slug(request.getSlug())
                .title(htmlSanitizerService.stripHtml(request.getTitle()))
                .subtitle(htmlSanitizerService.stripHtml(request.getSubtitle()))
                .content(htmlSanitizerService.sanitize(request.getContent()))
                .excerpt(htmlSanitizerService.stripHtml(request.getExcerpt()))
                .coverImageUrl(request.getCoverImageUrl())
                .authorId(authorId)
                .status(status)
                .scheduledAt(request.getScheduledAt())
                .readingTimeMinutes(calculateReadingTime(request.getContent()))
                .seoTitle(htmlSanitizerService.stripHtml(request.getSeoTitle()))
                .seoDescription(htmlSanitizerService.stripHtml(request.getSeoDescription()))
                .seoKeywords(htmlSanitizerService.stripHtml(request.getSeoKeywords()))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        if (article.getStatus() == ArticleStatus.PUBLISHED) {
            article.setPublishedAt(LocalDateTime.now());
        }

        return articleRepository.save(article)
                .onErrorResume(DataIntegrityViolationException.class, ex ->
                        Mono.error(new DuplicateResourceException("Article", "slug", request.getSlug())))
                .flatMap(saved -> {
                    if (tags.isEmpty()) {
                        return Mono.just(saved);
                    }
                    return saveArticleTags(saved.getId(), tags)
                            .then(Mono.just(saved));
                })
                .doOnSuccess(a -> {
                    log.info("Article created: {} (status: {})", a.getSlug(), a.getStatus());
                    if (a.getStatus() == ArticleStatus.PUBLISHED) {
                        notificationEventService.articlePublished(a.getTitle(), a.getSlug());
                    } else {
                        notificationEventService.articleCreated(a.getTitle(), a.getSlug());
                    }
                })
                .flatMap(articleService::enrichArticleWithMetadata)
                .map(articleService::mapToResponse);
    }

    /**
     * F-150: Resolve a unique slug. If the slug already exists, append a random suffix and retry.
     */
    private Mono<String> resolveUniqueSlug(String slug) {
        return articleRepository.existsBySlug(slug)
                .flatMap(exists -> {
                    if (!exists) return Mono.just(slug);
                    String suffixed = slug + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
                    log.info("Slug '{}' already exists, using '{}'", slug, suffixed);
                    return Mono.just(suffixed);
                });
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Mono<ArticleResponse> updateArticle(Long id, ArticleRequest request) {
        return articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> verifyOwnership(article).thenReturn(article))
                .flatMap(article -> {
                    return getCurrentUser()
                            .flatMap(user -> articleVersionService.createVersion(
                                    article,
                                    "Auto-saved before update",
                                    user.getId(),
                                    user.getName()
                            ))
                            .onErrorResume(e -> {
                                log.warn("Failed to create article version: {}", e.getMessage());
                                return Mono.empty();
                            })
                            .then(Mono.just(article));
                })
                .flatMap(article -> {
                    // BUG-06 fix: Check for slug collision with other articles
                    String newSlug = request.getSlug();
                    Mono<Void> slugCheck = Mono.empty();
                    if (newSlug != null && !newSlug.equals(article.getSlug())) {
                        slugCheck = articleRepository.existsBySlug(newSlug)
                                .flatMap(exists -> exists
                                        ? Mono.error(new DuplicateResourceException("Article", "slug", newSlug))
                                        : Mono.empty());
                    }

                    // Defer all entity mutations until after the slug check passes.
                    // Otherwise an error path can leave the entity in a partially-mutated
                    // state that a retry could persist.
                    return slugCheck.then(Mono.defer(() -> {
                        ArticleStatus newStatus = ArticleStatus.fromString(request.getStatus(), ArticleStatus.DRAFT);
                        ArticleStatus oldStatus = article.getStatus();

                        article.setSlug(request.getSlug());
                        article.setTitle(htmlSanitizerService.stripHtml(request.getTitle()));
                        article.setSubtitle(htmlSanitizerService.stripHtml(request.getSubtitle()));
                        article.setContent(htmlSanitizerService.sanitize(request.getContent()));
                        article.setExcerpt(htmlSanitizerService.stripHtml(request.getExcerpt()));
                        article.setCoverImageUrl(request.getCoverImageUrl());
                        article.setStatus(newStatus);
                        article.setReadingTimeMinutes(calculateReadingTime(request.getContent()));
                        article.setSeoTitle(htmlSanitizerService.stripHtml(request.getSeoTitle()));
                        article.setSeoDescription(htmlSanitizerService.stripHtml(request.getSeoDescription()));
                        article.setSeoKeywords(htmlSanitizerService.stripHtml(request.getSeoKeywords()));
                        article.setUpdatedAt(LocalDateTime.now());

                        if (oldStatus != ArticleStatus.PUBLISHED && newStatus == ArticleStatus.PUBLISHED) {
                            article.setPublishedAt(LocalDateTime.now());
                        }

                        return articleRepository.save(article);
                    }))
                            .onErrorResume(DataIntegrityViolationException.class, ex ->
                                    Mono.error(new DuplicateResourceException("Article", "slug", request.getSlug())))
                            .flatMap(saved -> {
                                if (request.getTagSlugs() == null) {
                                    return Mono.just(saved);
                                }
                                return deleteArticleTags(saved.getId())
                                        .then(fetchOrCreateTags(request.getTagSlugs()).collectList())
                                        .flatMap(tags -> {
                                            if (tags.isEmpty()) {
                                                return Mono.just(saved);
                                            }
                                            return saveArticleTags(saved.getId(), tags)
                                                    .then(Mono.just(saved));
                                        });
                            })
                            .doOnSuccess(a -> log.info("Article updated: {}", a.getSlug()))
                            .flatMap(articleService::enrichArticleWithMetadata)
                            .map(articleService::mapToResponse);
                });
    }

    @Transactional
    public Mono<Long> bulkUpdateStatus(List<Long> ids, String status) {
        ArticleStatus targetStatus;
        try {
            targetStatus = ArticleStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return Mono.error(new IllegalArgumentException("error.invalid_status"));
        }

        // R2DBC transactions are bound to a single connection, so concurrent flatMap
        // would not be atomic. Use concatMap to serialize updates within the tx.
        return getCurrentUser()
                .flatMap(user -> Flux.fromIterable(ids)
                        .concatMap(id -> articleRepository.findById(id)
                                .filter(article -> isAdmin(user) || isOwner(article, user))
                                .flatMap(article -> {
                                    article.setStatus(targetStatus);
                                    article.setUpdatedAt(LocalDateTime.now());
                                    if (targetStatus == ArticleStatus.PUBLISHED && article.getPublishedAt() == null) {
                                        article.setPublishedAt(LocalDateTime.now());
                                    }
                                    return articleRepository.save(article);
                                }))
                        .count())
                .flatMap(count -> invalidateFeedCaches().thenReturn(count))
                .doOnSuccess(count -> log.info("Bulk status update: {} articles → {}", count, status));
    }

    @Transactional
    public Mono<Void> deleteArticle(Long id) {
        // Q3.2: All article_id FKs have ON DELETE CASCADE (verified in V1 schema).
        // Deleting the article atomically removes tags, comments, bookmarks, versions,
        // analytics, translations, reviews, and reading history in a single transaction.
        // Cache invalidation runs outside the transaction boundary — stale entries are
        // harmless (re-populated on next read) and must not block the delete.
        return articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> verifyOwnership(article).thenReturn(article))
                .flatMap(article -> articleRepository.deleteById(id)
                        .doOnSuccess(v -> log.info("Article deleted: {} (slug={})", id, article.getSlug()))
                )
                .then(invalidateFeedCaches());
    }

    // ==================== REVIEW WORKFLOW ====================

    @Transactional
    public Mono<ArticleResponse> submitForReview(Long id) {
        return articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> verifyOwnership(article).thenReturn(article))
                .flatMap(article -> {
                    if (article.getStatus() != ArticleStatus.DRAFT) {
                        return Mono.error(new IllegalStateException("Only DRAFT articles can be submitted for review"));
                    }
                    article.setStatus(ArticleStatus.REVIEW);
                    article.setUpdatedAt(LocalDateTime.now());
                    return articleRepository.save(article);
                })
                .doOnSuccess(a -> {
                    log.info("Article submitted for review: {}", a.getSlug());
                    notificationEventService.articleSubmittedForReview(a.getTitle(), a.getSlug());
                })
                .flatMap(articleService::enrichArticleWithMetadata)
                .map(articleService::mapToResponse);
    }

    @Transactional
    public Mono<ArticleResponse> approveReview(Long id) {
        return articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> getCurrentUser().flatMap(user -> {
                    if (!isAdmin(user)) {
                        return Mono.error(new org.springframework.security.access.AccessDeniedException("Only admins can approve reviews"));
                    }
                    if (article.getStatus() != ArticleStatus.REVIEW) {
                        return Mono.error(new IllegalStateException("Only articles in REVIEW status can be approved"));
                    }
                    article.setStatus(ArticleStatus.PUBLISHED);
                    article.setPublishedAt(LocalDateTime.now());
                    article.setUpdatedAt(LocalDateTime.now());

                    ArticleReview review = ArticleReview.builder()
                            .id(idService.nextId())
                            .articleId(id)
                            .reviewerId(user.getId())
                            .status(ArticleReviewStatus.APPROVED)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return articleRepository.save(article)
                            .flatMap(saved -> articleReviewRepository.save(review).thenReturn(saved));
                }))
                .doOnSuccess(a -> {
                    log.info("Article review approved: {}", a.getSlug());
                    notificationEventService.articlePublished(a.getTitle(), a.getSlug());
                })
                .flatMap(article -> invalidateFeedCaches()
                        .then(notifySubscribersAboutNewArticle(article))
                        .thenReturn(article))
                .flatMap(articleService::enrichArticleWithMetadata)
                .map(articleService::mapToResponse);
    }

    @Transactional
    public Mono<ArticleResponse> requestChanges(Long id, String feedback) {
        return articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> getCurrentUser().flatMap(user -> {
                    if (!isAdmin(user)) {
                        return Mono.error(new org.springframework.security.access.AccessDeniedException("Only admins can request changes"));
                    }
                    if (article.getStatus() != ArticleStatus.REVIEW) {
                        return Mono.error(new IllegalStateException("Only articles in REVIEW status can have changes requested"));
                    }
                    article.setStatus(ArticleStatus.DRAFT);
                    article.setUpdatedAt(LocalDateTime.now());

                    ArticleReview review = ArticleReview.builder()
                            .id(idService.nextId())
                            .articleId(id)
                            .reviewerId(user.getId())
                            .status(ArticleReviewStatus.CHANGES_REQUESTED)
                            .feedback(feedback)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return articleRepository.save(article)
                            .flatMap(saved -> articleReviewRepository.save(review).thenReturn(saved));
                }))
                .doOnSuccess(a -> log.info("Changes requested for article: {}", a.getSlug()))
                .flatMap(articleService::enrichArticleWithMetadata)
                .map(articleService::mapToResponse);
    }

    public Flux<ArticleReview> getReviewHistory(Long id) {
        return articleReviewRepository.findByArticleIdOrderByCreatedAtDesc(id);
    }

    // ==================== PRIVATE HELPERS ====================

    private Mono<Void> invalidateFeedCaches() {
        return Mono.when(
                cacheService.delete(RSS_CACHE_KEY),
                cacheService.delete(SITEMAP_CACHE_KEY)
        ).doOnSuccess(v -> log.debug("Feed caches invalidated"));
    }

    private Mono<Void> notifySubscribersAboutNewArticle(Article article) {
        return subscriberRepository.findAllConfirmed(paginationConfig.getBulkQueryMax())
                // PERF-01: Limit concurrency to prevent SMTP overload
                .flatMap(subscriber -> emailService.sendNewArticleNotification(
                        subscriber.getEmail(),
                        subscriber.getName(),
                        article.getTitle(),
                        article.getSlug(),
                        article.getExcerpt(),
                        subscriber.getUnsubscribeToken()
                ).onErrorResume(e -> {
                    log.warn("Failed to send article notification to {}: {}", PiiMasker.maskEmail(subscriber.getEmail()), e.getMessage());
                    return Mono.empty();
                }), 10)
                .then()
                .doOnSuccess(v -> log.info("Notified subscribers about new article: {}", article.getSlug()));
    }

    private Flux<Tag> fetchOrCreateTags(java.util.Collection<String> tagSlugs) {
        if (tagSlugs == null || tagSlugs.isEmpty()) {
            return Flux.empty();
        }
        return Flux.fromIterable(tagSlugs)
                .flatMap(slug -> tagRepository.findBySlug(slug)
                        .switchIfEmpty(Mono.defer(() -> {
                            log.warn("Tag '{}' not found, skipping", slug);
                            return Mono.empty();
                        })), 8);
    }

    private Mono<Void> saveArticleTags(Long articleId, List<Tag> tags) {
        return Flux.fromIterable(tags)
                .flatMap(tag -> r2dbcTemplate.getDatabaseClient()
                        .sql("INSERT INTO article_tags (article_id, tag_id) VALUES (:articleId, :tagId)")
                        .bind("articleId", articleId)
                        .bind("tagId", tag.getId())
                        .fetch()
                        .rowsUpdated(), 8)
                .then();
    }

    // Q3.2: deleteArticle* helper methods removed — DB ON DELETE CASCADE handles the cleanup
    // atomically when the article is deleted. deleteArticleTags is kept because it's used
    // during article UPDATE to clear the tag mapping before re-inserting the new set.

    private Mono<Void> deleteArticleTags(Long articleId) {
        return r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM article_tags WHERE article_id = :articleId")
                .bind("articleId", articleId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Integer calculateReadingTime(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int wordCount = content.split("\\s+").length;
        return Math.max(1, wordCount / WORDS_PER_MINUTE);
    }

    // ARCH-3: delegates to the shared CurrentUserService.
    private Mono<User> getCurrentUser() {
        return currentUserService.currentUser();
    }

    // ==================== OWNERSHIP ENFORCEMENT ====================

    private boolean isAdmin(User user) {
        return UserRole.ADMIN.matches(user.getRole());
    }

    private boolean isOwner(Article article, User user) {
        return article.getAuthorId() != null && article.getAuthorId().equals(user.getId());
    }

    /**
     * Verify that the current user owns the article, or is an ADMIN.
     * DEV can only modify their own articles.
     */
    private Mono<Void> verifyOwnership(Article article) {
        return getCurrentUser()
                .flatMap(user -> {
                    if (isAdmin(user) || isOwner(article, user)) {
                        return Mono.empty();
                    }
                    return Mono.error(new AccessDeniedException(
                            "You can only manage your own articles"));
                });
    }
}
