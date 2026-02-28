package dev.catananti.service;

import dev.catananti.dto.ArticleRequest;
import dev.catananti.dto.ArticleResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.Tag;
import dev.catananti.entity.User;
import dev.catananti.entity.UserRole;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.SubscriberRepository;
import dev.catananti.repository.TagRepository;
import dev.catananti.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final TagRepository tagRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final SubscriberRepository subscriberRepository;
    private final EmailService emailService;
    private final ArticleVersionService articleVersionService;
    private final UserRepository userRepository;
    private final CacheService cacheService;
    private final IdService idService;
    private final NotificationEventService notificationEventService;
    private final HtmlSanitizerService htmlSanitizerService;
    private final ArticleService articleService;

    private static final String RSS_CACHE_KEY = "rss:feed";
    private static final String SITEMAP_CACHE_KEY = "sitemap:xml";

    // ==================== ADMIN CRUD ====================

    public Mono<PageResponse<ArticleResponse>> getAllArticles(int page, int size, String status) {
        int offset = page * size;

        return getCurrentUser().flatMap(user -> {
            Flux<Article> articlesFlux;
            Mono<Long> countMono;

            if (isAdmin(user)) {
                // ADMIN sees all articles
                if (status != null && !status.isEmpty()) {
                    articlesFlux = articleRepository.findByStatusOrderByCreatedAtDesc(status.toUpperCase(), size, offset);
                    countMono = articleRepository.countByStatus(status.toUpperCase());
                } else {
                    articlesFlux = articleRepository.findAllOrderByCreatedAtDesc(size, offset);
                    countMono = articleRepository.countAll();
                }
            } else {
                // DEV/EDITOR see only their own articles
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

    @Transactional
    public Mono<ArticleResponse> publishArticle(Long id) {
        return articleRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", id)))
                .flatMap(article -> verifyOwnership(article).thenReturn(article))
                .flatMap(article -> {
                    article.setStatus(ArticleStatus.PUBLISHED.name());
                    article.setPublishedAt(LocalDateTime.now());
                    article.setUpdatedAt(LocalDateTime.now());
                    return articleRepository.save(article);
                })
                .doOnSuccess(a -> {
                    log.info("Article published: {}", a.getSlug());
                    notificationEventService.articlePublished(a.getTitle(), a.getSlug());
                })
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
                    article.setStatus(ArticleStatus.DRAFT.name());
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
                    article.setStatus(ArticleStatus.ARCHIVED.name());
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
        return resolveUniqueSlug(request.getSlug())
                .flatMap(uniqueSlug -> {
                    request.setSlug(uniqueSlug);
                    return getCurrentUser()
                            .map(User::getId)
                            .defaultIfEmpty(0L)
                            .flatMap(userId -> {
                                Long authorId = userId != 0L ? userId : null;
                                return fetchOrCreateTags(request.getTagSlugs())
                                        .collectList()
                                        .flatMap(tags -> {
                                            String status = request.getStatus() != null ? request.getStatus().toUpperCase() : ArticleStatus.DRAFT.name();

                                            if (request.getScheduledAt() != null && !ArticleStatus.PUBLISHED.matches(status)) {
                                                status = ArticleStatus.SCHEDULED.name();
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

                                            if (ArticleStatus.PUBLISHED.matches(article.getStatus())) {
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
                                                        if (ArticleStatus.PUBLISHED.matches(a.getStatus())) {
                                                            notificationEventService.articlePublished(a.getTitle(), a.getSlug());
                                                        } else {
                                                            notificationEventService.articleCreated(a.getTitle(), a.getSlug());
                                                        }
                                                    })
                                                    .flatMap(articleService::enrichArticleWithMetadata)
                                                    .map(articleService::mapToResponse);
                                        });
                            });
                });
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

    @Transactional
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

                    String newStatus = request.getStatus() != null ? request.getStatus().toUpperCase() : ArticleStatus.DRAFT.name();
                    String oldStatus = article.getStatus();

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

                    if (!ArticleStatus.PUBLISHED.matches(oldStatus) && ArticleStatus.PUBLISHED.matches(newStatus)) {
                        article.setPublishedAt(LocalDateTime.now());
                    }

                    return slugCheck.then(articleRepository.save(article))
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
    public Mono<Void> deleteArticle(Long id) {
        return articleRepository.findById(id)
                .flatMap(article -> verifyOwnership(article).thenReturn(article))
                .flatMap(article -> deleteArticleTags(id)
                        .then(deleteArticleComments(id))
                        .then(deleteArticleBookmarks(id))
                        .then(deleteArticleVersions(id))
                        .then(articleRepository.deleteById(id))
                        .then(invalidateFeedCaches())
                        .doOnSuccess(v -> log.info("Article deleted: {} (slug={})", id, article.getSlug()))
                )
                .then();
    }

    // ==================== PRIVATE HELPERS ====================

    private Mono<Void> invalidateFeedCaches() {
        return Mono.when(
                cacheService.delete(RSS_CACHE_KEY),
                cacheService.delete(SITEMAP_CACHE_KEY)
        ).doOnSuccess(v -> log.debug("Feed caches invalidated"));
    }

    private Mono<Void> notifySubscribersAboutNewArticle(Article article) {
        return subscriberRepository.findAllConfirmed()
                // PERF-01: Limit concurrency to prevent SMTP overload
                .flatMap(subscriber -> emailService.sendNewArticleNotification(
                        subscriber.getEmail(),
                        subscriber.getName(),
                        article.getTitle(),
                        article.getSlug(),
                        article.getExcerpt(),
                        subscriber.getUnsubscribeToken()
                ).onErrorResume(e -> {
                    log.warn("Failed to send article notification to {}: {}", subscriber.getEmail(), e.getMessage());
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
                        })));
    }

    private Mono<Void> saveArticleTags(Long articleId, List<Tag> tags) {
        return Flux.fromIterable(tags)
                .flatMap(tag -> r2dbcTemplate.getDatabaseClient()
                        .sql("INSERT INTO article_tags (article_id, tag_id) VALUES (:articleId, :tagId)")
                        .bind("articleId", articleId)
                        .bind("tagId", tag.getId())
                        .fetch()
                        .rowsUpdated())
                .then();
    }

    private Mono<Void> deleteArticleTags(Long articleId) {
        return r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM article_tags WHERE article_id = :articleId")
                .bind("articleId", articleId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Void> deleteArticleComments(Long articleId) {
        return r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM comments WHERE article_id = :articleId")
                .bind("articleId", articleId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Void> deleteArticleBookmarks(Long articleId) {
        return r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM bookmarks WHERE article_id = :articleId")
                .bind("articleId", articleId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Void> deleteArticleVersions(Long articleId) {
        return r2dbcTemplate.getDatabaseClient()
                .sql("DELETE FROM article_versions WHERE article_id = :articleId")
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

    private Mono<User> getCurrentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> auth.getName())
                .flatMap(email -> userRepository.findByEmail(email));
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
     * DEV/EDITOR can only modify their own articles.
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
