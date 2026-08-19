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

    /**
     * AUD19C-2: editorial review gate (style cf. AdminArticleController#maxBulkSize).
     * When true, non-ADMIN users cannot transition an article to PUBLISHED through any
     * path except the approve-review flow (which is ADMIN-only anyway) — they must
     * submit for review instead. Default false keeps today's behavior.
     */
    @org.springframework.beans.factory.annotation.Value("${app.articles.require-review-for-publish:false}")
    private boolean requireReviewForPublish;

    /**
     * AUD19C-1: tolerated clock skew when validating that a scheduled publication
     * date is in the future — editors clicking "schedule for now" plus request
     * latency must not bounce with a validation error.
     */
    private static final java.time.Duration SCHEDULE_PAST_SKEW = java.time.Duration.ofMinutes(1);

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
                // AUD19C-2: review gate — when enabled, only ADMINs publish directly.
                .flatMap(article -> verifyPublishAllowed(article.getStatus(), false).thenReturn(article))
                .flatMap(article -> {
                    article.setStatus(ArticleStatus.PUBLISHED);
                    // AUD19C-1: publishing kills any stale schedule left on the row.
                    article.setScheduledAt(null);
                    // Preserve the original publish timestamp on republish so feed/RSS
                    // ordering is not shuffled every time an already-published article is
                    // re-published.
                    if (article.getPublishedAt() == null) {
                        article.setPublishedAt(LocalDateTime.now());
                    }
                    article.setUpdatedAt(LocalDateTime.now());
                    return articleRepository.save(article);
                })
                .doOnSuccess(a -> log.info("Article published: {}", a.getSlug()));
        return transactionalOperator.transactional(publish)
                // AUD19C-2: shared post-commit publish side effects (SSE + caches +
                // notified_at-gated subscriber fan-out).
                .flatMap(this::applyPublishSideEffects)
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
                    // AUD19C-1: unpublishing must clear any schedule residue, otherwise the
                    // scheduler could resurrect the article from a stale scheduled_at.
                    article.setScheduledAt(null);
                    article.setUpdatedAt(LocalDateTime.now());
                    return articleRepository.save(article);
                })
                .doOnSuccess(a -> log.info("Article unpublished: {}", a.getSlug()))
                .flatMap(article -> invalidateArticleAndFeedCaches(article.getSlug()).thenReturn(article))
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
                .flatMap(article -> invalidateArticleAndFeedCaches(article.getSlug()).thenReturn(article))
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
        // AUD19C-1: create-side auto-promotion kept as-is (a scheduledAt on a
        // non-PUBLISHED create means "schedule it"); the update path deliberately
        // does NOT auto-promote — see updateArticle.
        ArticleStatus status = ArticleStatus.fromString(request.getStatus(), ArticleStatus.DRAFT);

        if (request.getScheduledAt() != null && status != ArticleStatus.PUBLISHED) {
            status = ArticleStatus.SCHEDULED;
        }

        // AUD19C-1: same schedule validation as the update path — a SCHEDULED article
        // must carry a date, and that date must not already be in the past.
        if (status == ArticleStatus.SCHEDULED) {
            if (request.getScheduledAt() == null) {
                return Mono.error(new IllegalArgumentException("error.scheduled_at_required"));
            }
            if (isInPast(request.getScheduledAt())) {
                return Mono.error(new IllegalArgumentException("error.scheduled_at_past"));
            }
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
                // AUD19C-1: scheduled_at only carries meaning for SCHEDULED articles —
                // never persist it alongside another status (stale-schedule residue).
                .scheduledAt(status == ArticleStatus.SCHEDULED ? request.getScheduledAt() : null)
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

        // AUD19C-2: review gate applies to create-as-PUBLISHED as well.
        Mono<Void> publishGate = article.getStatus() == ArticleStatus.PUBLISHED
                ? verifyPublishAllowed(null, false)
                : Mono.empty();

        return publishGate.then(articleRepository.save(article))
                .onErrorResume(DataIntegrityViolationException.class, ex ->
                        Mono.error(new DuplicateResourceException("Article", "slug", request.getSlug())))
                .flatMap(saved -> {
                    if (tags.isEmpty()) {
                        return Mono.just(saved);
                    }
                    return saveArticleTags(saved.getId(), tags)
                            .then(Mono.just(saved));
                })
                .doOnSuccess(a -> log.info("Article created: {} (status: {})", a.getSlug(), a.getStatus()))
                .flatMap(a -> {
                    if (a.getStatus() == ArticleStatus.PUBLISHED) {
                        // AUD19C-2: creating directly as PUBLISHED previously sent no
                        // subscriber e-mail and no fan-out — route through the shared
                        // side effects (SSE + caches + gated e-mail).
                        return applyPublishSideEffects(a);
                    }
                    notificationEventService.articleCreated(a.getTitle(), a.getSlug());
                    return invalidateArticleAndFeedCaches(a.getSlug()).thenReturn(a);
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
                    // TX-09: do NOT swallow createVersion errors here. This runs inside the
                    // update transaction — after a failed SQL statement PostgreSQL marks the
                    // whole transaction aborted (25P02), so continuing the chain would turn a
                    // versioning error into a confusing "current transaction is aborted"
                    // failure on the article save. Let it propagate and roll back cleanly.
                    return getCurrentUser()
                            .flatMap(user -> articleVersionService.createVersion(
                                    article,
                                    "Auto-saved before update",
                                    user.getId(),
                                    user.getName()
                            ))
                            .then(Mono.just(article));
                })
                .flatMap(article -> {
                    // Capture the slug before any mutation so both the old and new
                    // slug caches can be invalidated after the save.
                    final String oldSlug = article.getSlug();
                    // BUG-06 fix: Check for slug collision with other articles
                    String newSlug = request.getSlug();
                    Mono<Void> slugCheck = Mono.empty();
                    if (newSlug != null && !newSlug.equals(article.getSlug())) {
                        slugCheck = articleRepository.existsBySlug(newSlug)
                                .flatMap(exists -> exists
                                        ? Mono.error(new DuplicateResourceException("Article", "slug", newSlug))
                                        : Mono.empty());
                    }

                    // When the request omits the status, keep the article's current
                    // status instead of silently demoting a PUBLISHED article to DRAFT.
                    // AUD19C-1 (documented choice): unlike the create path, the update
                    // path does NOT auto-promote to SCHEDULED when a scheduledAt is
                    // present — the editor's explicit status always wins on update.
                    final ArticleStatus newStatus = ArticleStatus.fromString(request.getStatus(), article.getStatus());
                    final ArticleStatus oldStatus = article.getStatus();
                    final boolean publishTransition =
                            oldStatus != ArticleStatus.PUBLISHED && newStatus == ArticleStatus.PUBLISHED;

                    // AUD19C-2: gate transitions to PUBLISHED. The hard REVIEW rule is
                    // enforced here (a non-ADMIN must not flip REVIEW→PUBLISHED via PUT;
                    // that transition belongs to approveReview) in addition to the
                    // require-review-for-publish flag.
                    Mono<Void> publishGate = publishTransition
                            ? verifyPublishAllowed(oldStatus, true)
                            : Mono.empty();

                    // Defer all entity mutations until after the slug check passes.
                    // Otherwise an error path can leave the entity in a partially-mutated
                    // state that a retry could persist.
                    return slugCheck.then(publishGate).then(Mono.defer(() -> {
                        // AUD19C-1: scheduledAt handling on update.
                        if (newStatus == ArticleStatus.SCHEDULED) {
                            LocalDateTime effectiveSchedule = request.getScheduledAt() != null
                                    ? request.getScheduledAt()
                                    : article.getScheduledAt();
                            if (effectiveSchedule == null) {
                                return Mono.error(new IllegalArgumentException("error.scheduled_at_required"));
                            }
                            if (isInPast(effectiveSchedule)) {
                                return Mono.error(new IllegalArgumentException("error.scheduled_at_past"));
                            }
                            article.setScheduledAt(effectiveSchedule);
                        } else {
                            // Any non-SCHEDULED status kills a stale schedule, otherwise the
                            // scheduler would later fire (and e-mail-blast) on a leftover date.
                            article.setScheduledAt(null);
                        }

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

                        // CC-05: when the editor sent the version it loaded, save against it —
                        // a stale version makes the UPDATE match 0 rows and surface as 409.
                        if (request.getVersion() != null) {
                            article.setVersion(request.getVersion());
                        }

                        // AUD19C-2: set-if-null, mirroring publishArticle — republishing via
                        // PUT must not shuffle feed/RSS ordering.
                        if (publishTransition && article.getPublishedAt() == null) {
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
                            .flatMap(saved -> {
                                String savedSlug = saved.getSlug();
                                Mono<Void> invalidate = oldSlug.equals(savedSlug)
                                        ? invalidateArticleAndFeedCaches(savedSlug)
                                        : Mono.when(
                                                invalidateFeedCaches(),
                                                cacheService.invalidateArticle(oldSlug),
                                                cacheService.invalidateArticle(savedSlug));
                                return invalidate.thenReturn(saved);
                            })
                            // AUD19C-2: publishing via PUT previously fired NO SSE event and
                            // NO subscriber e-mail — route through the shared side effects.
                            // Note: updateArticle is annotation-@Transactional, so unlike
                            // PATCH publish/approveReview these side effects run within the
                            // update transaction; the notified_at CAS still guarantees the
                            // fan-out happens at most once per article.
                            .flatMap(saved -> publishTransition ? applyPublishSideEffects(saved) : Mono.just(saved))
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

        // AUD19C-1: SCHEDULED is not a valid bulk target — the bulk request carries no
        // dates, so it would either strand articles with scheduled_at NULL (never
        // published) or re-arm a stale leftover date (surprise publish + e-mail blast).
        if (targetStatus == ArticleStatus.SCHEDULED) {
            return Mono.error(new IllegalArgumentException("error.bulk_scheduled_not_allowed"));
        }

        // R2DBC transactions are bound to a single connection, so concurrent flatMap
        // would not be atomic. Use concatMap to serialize updates within the tx.
        return getCurrentUser()
                .flatMap(user -> Flux.fromIterable(ids)
                        .concatMap(id -> articleRepository.findById(id)
                                // Bulk semantics: silently skip articles the caller may not
                                // touch. AUD19C-2 extends the same silent filter to publish
                                // transitions a non-ADMIN is not allowed to make (review
                                // gate flag, and REVIEW articles which only approveReview
                                // may publish).
                                .filter(article -> isAdmin(user)
                                        || (isOwner(article, user)
                                            && isBulkTransitionAllowedForNonAdmin(article, targetStatus)))
                                .flatMap(article -> {
                                    boolean publishTransition = targetStatus == ArticleStatus.PUBLISHED
                                            && article.getStatus() != ArticleStatus.PUBLISHED;
                                    article.setStatus(targetStatus);
                                    // AUD19C-1: target is never SCHEDULED here (rejected above),
                                    // so any leftover schedule is stale — clear it.
                                    article.setScheduledAt(null);
                                    article.setUpdatedAt(LocalDateTime.now());
                                    if (targetStatus == ArticleStatus.PUBLISHED && article.getPublishedAt() == null) {
                                        article.setPublishedAt(LocalDateTime.now());
                                    }
                                    return articleRepository.save(article)
                                            // AUD19C-2: bulk publish previously fired no SSE and
                                            // no subscriber e-mail — apply the shared side effects
                                            // per article (the notified_at CAS keeps it once-only).
                                            .flatMap(saved -> publishTransition
                                                    ? applyPublishSideEffects(saved)
                                                    : Mono.just(saved));
                                }))
                        .count())
                .flatMap(count -> Mono.when(invalidateFeedCaches(), cacheService.invalidateAllArticles())
                        .thenReturn(count))
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
                        .then(invalidateArticleAndFeedCaches(article.getSlug()))
                );
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

    public Mono<ArticleResponse> approveReview(Long id) {
        // TX-07: same shape as publishArticle — only the DB writes run inside the
        // transaction; cache invalidation and the subscriber email fan-out happen
        // after commit so no pool connection is held during SMTP sends.
        Mono<Article> approve = articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> getCurrentUser().flatMap(user -> {
                    if (!isAdmin(user)) {
                        return Mono.error(new org.springframework.security.access.AccessDeniedException("Only admins can approve reviews"));
                    }
                    if (article.getStatus() != ArticleStatus.REVIEW) {
                        return Mono.error(new IllegalStateException("Only articles in REVIEW status can be approved"));
                    }
                    article.setStatus(ArticleStatus.PUBLISHED);
                    // AUD19C-2: set-if-null (was an unconditional overwrite) — re-approving
                    // a previously published article must not shuffle feed/RSS ordering.
                    if (article.getPublishedAt() == null) {
                        article.setPublishedAt(LocalDateTime.now());
                    }
                    // AUD19C-1: publishing kills any stale schedule left on the row.
                    article.setScheduledAt(null);
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
                .doOnSuccess(a -> log.info("Article review approved: {}", a.getSlug()));
        return transactionalOperator.transactional(approve)
                // AUD19C-2: shared post-commit publish side effects (SSE + caches +
                // notified_at-gated subscriber fan-out).
                .flatMap(this::applyPublishSideEffects)
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

    // ==================== PUBLISH SIDE EFFECTS (AUD19C-2) ====================

    /**
     * Centralized side effects for every transition to PUBLISHED — SSE broadcast,
     * article/feed cache invalidation, and the subscriber e-mail fan-out.
     *
     * <p>The fan-out is gated by an atomic compare-and-swap on {@code notified_at}
     * ({@link ArticleRepository#claimSubscriberNotification}): only the caller that
     * flips it from NULL wins (rows == 1) and sends the announcement. Republishing
     * through any path — PATCH publish, PUT update, bulk status, re-approval, the
     * scheduler racing a manual publish — therefore never e-mails subscribers twice.</p>
     *
     * <p>TX-07 style: callers that use {@code transactionalOperator} (PATCH publish,
     * approveReview, the scheduler's claim) invoke this after commit so no pool
     * connection is held during SMTP sends. Annotation-transactional callers
     * (PUT update, bulk, create-as-published) invoke it inside their transaction;
     * the CAS semantics are identical either way.</p>
     */
    public Mono<Article> applyPublishSideEffects(Article article) {
        notificationEventService.articlePublished(article.getTitle(), article.getSlug());
        return invalidateArticleAndFeedCaches(article.getSlug())
                .then(articleRepository.claimSubscriberNotification(article.getId(), LocalDateTime.now()))
                .defaultIfEmpty(0)
                .flatMap(rows -> {
                    if (rows != null && rows > 0) {
                        return notifySubscribersAboutNewArticle(article);
                    }
                    log.debug("Subscribers already notified about article {} — skipping e-mail fan-out",
                            article.getSlug());
                    return Mono.empty();
                })
                .thenReturn(article);
    }

    /**
     * AUD19C-2: gate on transitions to PUBLISHED. ADMINs always pass.
     * For non-ADMINs:
     * <ul>
     *   <li>when {@code app.articles.require-review-for-publish} is true, every direct
     *       publish is rejected (403) — the review workflow is the only way in;</li>
     *   <li>independently of the flag, when {@code enforceReviewTransitionRule} is set
     *       (PUT update), a REVIEW article cannot be flipped to PUBLISHED — that
     *       transition belongs to {@link #approveReview} (ADMIN-only).</li>
     * </ul>
     * Mirrors {@link #verifyOwnership}: an empty current user (no auth context, e.g.
     * internal callers) passes through.
     */
    private Mono<Void> verifyPublishAllowed(ArticleStatus currentStatus, boolean enforceReviewTransitionRule) {
        return getCurrentUser().flatMap(user -> {
            if (isAdmin(user)) {
                return Mono.<Void>empty();
            }
            if (requireReviewForPublish) {
                return Mono.error(new AccessDeniedException(
                        "Publishing requires review approval"));
            }
            if (enforceReviewTransitionRule && currentStatus == ArticleStatus.REVIEW) {
                return Mono.error(new AccessDeniedException(
                        "Articles in review can only be published through review approval"));
            }
            return Mono.<Void>empty();
        });
    }

    /** AUD19C-2: bulk variant of the publish gate — used as a silent per-article filter. */
    private boolean isBulkTransitionAllowedForNonAdmin(Article article, ArticleStatus targetStatus) {
        if (targetStatus != ArticleStatus.PUBLISHED) {
            return true;
        }
        return !requireReviewForPublish && article.getStatus() != ArticleStatus.REVIEW;
    }

    /** AUD19C-1: schedule-date pastness check with a small tolerated clock skew. */
    private boolean isInPast(LocalDateTime scheduledAt) {
        return scheduledAt.isBefore(LocalDateTime.now().minus(SCHEDULE_PAST_SKEW));
    }

    // ==================== PRIVATE HELPERS ====================

    private Mono<Void> invalidateFeedCaches() {
        return Mono.when(
                cacheService.delete(RSS_CACHE_KEY),
                cacheService.delete(SITEMAP_CACHE_KEY)
        ).doOnSuccess(v -> log.debug("Feed caches invalidated"));
    }

    /**
     * Invalidate both the public article read-through cache (slug + published-page
     * entries, including locale variants) and the RSS/sitemap feed caches. Called
     * after every mutating article operation so unpublished/deleted/edited content
     * is not served stale from Redis.
     */
    private Mono<Void> invalidateArticleAndFeedCaches(String slug) {
        return Mono.when(
                invalidateFeedCaches(),
                cacheService.invalidateArticle(slug)
        );
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
