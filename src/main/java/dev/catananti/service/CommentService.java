package dev.catananti.service;

import dev.catananti.dto.CommentRequest;
import dev.catananti.dto.CommentResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Comment;
import dev.catananti.entity.CommentStatus;
import dev.catananti.entity.UserRole;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.config.PaginationConfig;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Collections;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommentService {

    private final CommentRepository commentRepository;
    private final ArticleRepository articleRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final HtmlSanitizerService htmlSanitizerService;
    private final ContentModerationService contentModerationService;
    private final IdService idService;
    private final NotificationEventService notificationEventService;
    private final BlogMetrics blogMetrics;
    private final PaginationConfig paginationConfig;
    private final CurrentUserService currentUserService;

    private static final long TRUSTED_COMMENTER_THRESHOLD = 3;

    // ==================== PUBLIC ENDPOINTS ====================

    public Flux<CommentResponse> getApprovedCommentsByArticleSlug(String slug) {
        return articleRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "slug", slug)))
                .flatMapMany(article -> commentRepository.findApprovedByArticleId(article.getId(), paginationConfig.getCommentTreeMax()))
                .collectList()
                .flatMap(this::batchEnrichWithReplies)
                .flatMapIterable(rootComments -> rootComments)
                .map(this::toPublicResponse);
    }

    public Mono<PageResponse<CommentResponse>> getApprovedCommentsByArticleSlugPaginated(String slug, int page, int size) {
        return getApprovedCommentsByArticleSlugPaginated(slug, page, size, "liked");
    }

    public Mono<PageResponse<CommentResponse>> getApprovedCommentsByArticleSlugPaginated(String slug, int page, int size, String sort) {
        int offset = page * size;
        return articleRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "slug", slug)))
                .flatMap(article -> {
                    Flux<Comment> commentsFlux = switch (sort) {
                        case "liked" -> commentRepository.findApprovedByArticleIdSortedByLikes(article.getId(), size, offset);
                        case "oldest" -> commentRepository.findApprovedByArticleIdSortedByOldest(article.getId(), size, offset);
                        default -> commentRepository.findApprovedByArticleIdPaginated(article.getId(), size, offset);
                    };
                    // Q9.1: Batch-load replies for all root comments to avoid N+1
                    return commentsFlux
                            .collectList()
                            .flatMap(this::batchEnrichWithReplies)
                            .map(enriched -> enriched.stream().map(this::toPublicResponse).toList())
                            .zipWith(commentRepository.countApprovedByArticleId(article.getId()))
                            .map(tuple -> {
                                var content = tuple.getT1();
                                var total = tuple.getT2();
                                return PageResponse.of(content, page, size, total);
                            });
                });
    }

    public Mono<Long> getCommentCountByArticleSlug(String slug) {
        return articleRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "slug", slug)))
                .flatMap(article -> commentRepository.countApprovedByArticleId(article.getId()));
    }

    public Mono<Integer> likeCommentAndReturnCount(Long commentId) {
        return commentRepository.incrementLikes(commentId)
                .then(commentRepository.getLikesCount(commentId))
                .defaultIfEmpty(0);
    }

    public Mono<Integer> unlikeCommentAndReturnCount(Long commentId) {
        return commentRepository.decrementLikes(commentId)
                .then(commentRepository.getLikesCount(commentId))
                .defaultIfEmpty(0);
    }

    public Mono<Integer> getCommentLikeCount(Long commentId) {
        return commentRepository.findById(commentId)
                .map(c -> c.getLikesCount() != null ? c.getLikesCount() : 0)
                .defaultIfEmpty(0);
    }

    @Transactional
    public Mono<CommentResponse> createComment(String slug, CommentRequest request) {
        return articleRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "slug", slug)))
                .flatMap(article -> {
                    // Validate parent comment if it's a reply
                    // YouTube Shorts style: max 1 level of nesting.
                    // Replies to replies are flattened to point at the root comment.
                    Mono<Void> parentValidation = Mono.empty();
                    if (request.getParentId() != null) {
                        parentValidation = commentRepository.findById(request.getParentId())
                                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Parent comment", "id", request.getParentId())))
                                .filter(parent -> parent.getArticleId().equals(article.getId()))
                                .switchIfEmpty(Mono.error(new IllegalArgumentException("error.comment_wrong_article")))
                                .flatMap(parent -> {
                                    if (parent.getParentId() != null) {
                                        request.setParentId(parent.getParentId());
                                    }
                                    return Mono.just(parent);
                                })
                                .then();
                    }

                    return parentValidation.then(Mono.defer(() -> {
                        // F-166: Sanitize user input to prevent XSS attacks
                        String sanitizedContent = htmlSanitizerService.stripHtml(request.getContent());
                        String sanitizedAuthorName = htmlSanitizerService.stripHtml(request.getAuthorName());

                        // F-167: Spam filter check
                        if (isSpam(sanitizedContent)) {
                            return Mono.error(new IllegalArgumentException("error.comment_spam_detected"));
                        }

                        // Content moderation: profanity + auto-approval
                        // F-ASYNC-05: Offload content moderation from reactive thread
                        return Mono.fromCallable(() ->
                                contentModerationService.analyzeContent(sanitizedContent, "en"))
                                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                                .flatMap(modResult ->

                        commentRepository.countApprovedByAuthorEmail(request.getAuthorEmail())
                                .defaultIfEmpty(0L)
                                .flatMap(approvedCount -> {
                                    boolean isTrusted = approvedCount >= TRUSTED_COMMENTER_THRESHOLD;
                                    CommentStatus status = determineCommentStatus(modResult, isTrusted);
                                    String moderationNote = modResult.getReasons().isEmpty() ? null
                                            : String.join("; ", modResult.getReasons());

                                    Comment comment = Comment.builder()
                                            .id(idService.nextId())
                                            .articleId(article.getId())
                                            .authorName(sanitizedAuthorName)
                                            .authorEmail(request.getAuthorEmail())
                                            .content(sanitizedContent)
                                            .status(status)
                                            .moderationNote(moderationNote)
                                            .parentId(request.getParentId())
                                            .userId(request.getUserId())
                                            .createdAt(LocalDateTime.now())
                                            .build();

                                    return commentRepository.save(comment)
                                            .flatMap(savedComment -> {
                                                // Notify article author only for pending comments
                                                if (status == CommentStatus.PENDING && article.getAuthorId() != null) {
                                                    return userRepository.findById(article.getAuthorId())
                                                            .flatMap(author -> emailService.sendCommentNotification(
                                                                    author.getEmail(),
                                                                    author.getName(),
                                                                    savedComment.getAuthorName(),
                                                                    article.getTitle(),
                                                                    article.getSlug(),
                                                                    savedComment.getContent()
                                                            ).onErrorResume(e -> {
                                                                log.warn("Failed to send comment notification to {}: {}",
                                                                        PiiMasker.maskEmail(author.getEmail()), e.getMessage());
                                                                return Mono.empty();
                                                            }))
                                                            .thenReturn(savedComment);
                                                }
                                                return Mono.just(savedComment);
                                            })
                                            .doOnSuccess(c -> {
                                                log.info("Comment created for article {} by {} ({}) status={}: {}",
                                                        slug, c.getAuthorName(), PiiMasker.maskEmail(c.getAuthorEmail()), c.getStatus(), c.getId());
                                                if (c.getStatus() != CommentStatus.REJECTED) {
                                                    notificationEventService.commentReceived(slug, c.getAuthorName());
                                                }
                                                blogMetrics.incrementCommentCreated();
                                            })
                                            .map(this::toPublicResponse);
                                }));
                    }));
                });
    }

    // ==================== ADMIN ENDPOINTS ====================

    public Mono<PageResponse<CommentResponse>> getAllCommentsPaginated(int page, int size) {
        int offset = page * size;
        return commentRepository.findAllPaginated(size, offset)
                .map(this::toResponse)
                .collectList()
                .zipWith(commentRepository.count())
                .flatMap(tuple -> enrichCommentsWithArticleInfo(tuple.getT1(), page, size, tuple.getT2()));
    }

    public Mono<PageResponse<CommentResponse>> getCommentsByStatus(String status, int page, int size) {
        int offset = page * size;
        
        return commentRepository.findByStatus(status.toUpperCase(), size, offset)
                .map(this::toResponse)
                .collectList()
                .zipWith(commentRepository.countByStatus(status.toUpperCase()))
                .flatMap(tuple -> enrichCommentsWithArticleInfo(tuple.getT1(), page, size, tuple.getT2()));
    }

    private Mono<PageResponse<CommentResponse>> enrichCommentsWithArticleInfo(
            List<CommentResponse> comments, int page, int size, long total) {
        var articleIds = comments.stream()
                .map(c -> Long.valueOf(c.getArticleId()))
                .distinct()
                .toList();

        if (articleIds.isEmpty()) {
            return Mono.just(PageResponse.of(comments, page, size, total));
        }

        return articleRepository.findAllById(articleIds)
                .collectMap(article -> String.valueOf(article.getId()))
                .map(articleMap -> {
                    comments.forEach(c -> {
                        var article = articleMap.get(c.getArticleId());
                        if (article != null) {
                            c.setArticleSlug(article.getSlug());
                            c.setArticleTitle(article.getTitle());
                        }
                    });
                    return PageResponse.of(comments, page, size, total);
                });
    }

    public Flux<CommentResponse> getAllCommentsByArticleId(Long articleId) {
        return commentRepository.findAllByArticleId(articleId, paginationConfig.getCommentTreeMax())
                .map(this::toResponse);
    }

    @Transactional
    public Mono<CommentResponse> approveComment(Long id) {
        return updateCommentStatus(id, CommentStatus.APPROVED);
    }

    @Transactional
    public Mono<CommentResponse> rejectComment(Long id) {
        return updateCommentStatus(id, CommentStatus.REJECTED);
    }

    @Transactional
    public Mono<CommentResponse> markAsSpam(Long id) {
        return updateCommentStatus(id, CommentStatus.SPAM);
    }

    @Transactional
    public Flux<CommentResponse> bulkApprove(List<Long> ids) {
        // R2DBC transactions are bound to a single connection, so concurrent flatMap
        // would not be atomic. Use concatMap to serialize updates within the tx.
        return Flux.fromIterable(ids).concatMap(this::approveComment);
    }

    @Transactional
    public Flux<CommentResponse> bulkReject(List<Long> ids) {
        // R2DBC transactions are bound to a single connection, so concurrent flatMap
        // would not be atomic. Use concatMap to serialize updates within the tx.
        return Flux.fromIterable(ids).concatMap(this::rejectComment);
    }

    @Transactional
    public Flux<CommentResponse> bulkMarkAsSpam(List<Long> ids) {
        // R2DBC transactions are bound to a single connection, so concurrent flatMap
        // would not be atomic. Use concatMap to serialize updates within the tx.
        return Flux.fromIterable(ids).concatMap(this::markAsSpam);
    }

    @Transactional
    public Mono<Void> deleteComment(Long id) {
        return commentRepository.findById(id)
                .flatMap(comment -> 
                        // Delete child replies first to avoid orphans (BUG-09)
                        commentRepository.deleteByParentId(id)
                                .then(commentRepository.deleteById(id))
                        .doOnSuccess(v -> log.info("Comment deleted (with replies): {}", id))
                )
                .then(); // Idempotent: if comment not found, complete silently
    }

    // ==================== ADMIN ENDPOINTS (ownership-scoped) ====================

    /**
     * Get comments by status, scoped by ownership.
     * ADMIN sees all comments; DEV see only comments on their own articles.
     */
    public Mono<PageResponse<CommentResponse>> getAdminCommentsByStatus(String status, int page, int size) {
        int offset = page * size;
        return getCurrentUser().flatMap(user -> {
            if (UserRole.ADMIN.matches(user.getRole())) {
                // ADMIN: existing behavior
                if ("ALL".equalsIgnoreCase(status)) {
                    return getAllCommentsPaginated(page, size);
                }
                return getCommentsByStatus(status, page, size);
            } else {
                // DEV: only comments on own articles
                Long userId = user.getId();
                Flux<Comment> commentsFlux;
                Mono<Long> countMono;
                if ("ALL".equalsIgnoreCase(status)) {
                    commentsFlux = commentRepository.findByArticleAuthorId(userId, size, offset);
                    countMono = commentRepository.countByArticleAuthorId(userId);
                } else {
                    commentsFlux = commentRepository.findByArticleAuthorIdAndStatus(userId, status.toUpperCase(), size, offset);
                    countMono = commentRepository.countByArticleAuthorIdAndStatus(userId, status.toUpperCase());
                }
                return commentsFlux
                        .map(this::toResponse)
                        .collectList()
                        .zipWith(countMono)
                        .flatMap(tuple -> enrichCommentsWithArticleInfo(tuple.getT1(), page, size, tuple.getT2()));
            }
        });
    }

    /**
     * Get comments by article, scoped by ownership.
     * ADMIN sees all; DEV only see comments on articles they authored.
     */
    public Flux<CommentResponse> getAdminCommentsByArticleId(Long articleId) {
        return getCurrentUser().flatMapMany(user -> {
            if (UserRole.ADMIN.matches(user.getRole())) {
                return getAllCommentsByArticleId(articleId);
            }
            // Verify the article belongs to this user
            return articleRepository.findById(articleId)
                    .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "id", articleId)))
                    .flatMapMany(article -> {
                        if (!user.getId().equals(article.getAuthorId())) {
                            return Flux.error(new AccessDeniedException("You can only view comments on your own articles"));
                        }
                        return getAllCommentsByArticleId(articleId);
                    });
        });
    }

    @Transactional
    public Mono<CommentResponse> adminApproveComment(Long id) {
        return verifyCommentOwnership(id).then(approveComment(id));
    }

    @Transactional
    public Mono<CommentResponse> adminRejectComment(Long id) {
        return verifyCommentOwnership(id).then(rejectComment(id));
    }

    @Transactional
    public Mono<CommentResponse> adminMarkAsSpam(Long id) {
        return verifyCommentOwnership(id).then(markAsSpam(id));
    }

    @Transactional
    public Mono<Void> adminDeleteComment(Long id) {
        return verifyCommentOwnership(id).then(deleteComment(id));
    }

    // ==================== HELPER METHODS ====================

    private Mono<CommentResponse> updateCommentStatus(Long id, CommentStatus status) {
        return commentRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Comment", "id", id)))
                .flatMap(comment -> {
                    comment.setStatus(status);
                    comment.setUpdatedAt(LocalDateTime.now());
                    return commentRepository.save(comment);
                })
                .doOnSuccess(c -> {
                    log.info("Comment {} status updated to: {}", id, status);
                    if (status == CommentStatus.APPROVED) {
                        notificationEventService.commentApproved(id);
                    }
                })
                .map(this::toResponse);
    }

    /**
     * Q9.1: Batch-load all replies for a list of root comments in a single query,
     * eliminating the N+1 problem from the paginated endpoint.
     */
    private Mono<List<Comment>> batchEnrichWithReplies(List<Comment> rootComments) {
        if (rootComments.isEmpty()) {
            return Mono.just(rootComments);
        }
        var parentIds = rootComments.stream().map(Comment::getId).toList();
        return commentRepository.findApprovedRepliesByParentIds(parentIds)
                .collectList()
                .map(allReplies -> {
                    var repliesByParentId = allReplies.stream()
                            .collect(java.util.stream.Collectors.groupingBy(Comment::getParentId));
                    rootComments.forEach(root -> root.setReplies(
                            repliesByParentId.getOrDefault(root.getId(), Collections.emptyList())));
                    return rootComments;
                });
    }

    /**
     * Map comment to public response (omits authorEmail for privacy).
     */
    private CommentResponse toPublicResponse(Comment comment) {
        return CommentResponse.builder()
                .id(String.valueOf(comment.getId()))
                .articleId(String.valueOf(comment.getArticleId()))
                .authorName(comment.getAuthorName())
                .content(comment.getContent())
                .status(comment.getStatus().name())
                .parentId(comment.getParentId() != null ? String.valueOf(comment.getParentId()) : null)
                .likesCount(comment.getLikesCount() != null ? comment.getLikesCount() : 0)
                .replies(comment.getReplies() != null ?
                        comment.getReplies().stream().map(this::toPublicResponse).toList() :
                        Collections.emptyList())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt() != null ? comment.getUpdatedAt() : comment.getCreatedAt())
                .build();
    }

    /**
     * Map comment to admin response (includes authorEmail).
     */
    private CommentResponse toResponse(Comment comment) {
        return CommentResponse.builder()
                .id(String.valueOf(comment.getId()))
                .articleId(String.valueOf(comment.getArticleId()))
                .authorName(comment.getAuthorName())
                .authorEmail(comment.getAuthorEmail())
                .content(comment.getContent())
                .status(comment.getStatus().name())
                .moderationNote(comment.getModerationNote())
                .parentId(comment.getParentId() != null ? String.valueOf(comment.getParentId()) : null)
                .likesCount(comment.getLikesCount() != null ? comment.getLikesCount() : 0)
                .replies(comment.getReplies() != null ? 
                        comment.getReplies().stream().map(this::toResponse).toList() : 
                        Collections.emptyList())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt() != null ? comment.getUpdatedAt() : comment.getCreatedAt())
                .build();
    }

    // ==================== SPAM FILTER ====================

    private static final java.util.regex.Pattern REPEATED_CHARS = java.util.regex.Pattern.compile("(.)\\1{10,}");
    private static final java.util.regex.Pattern URL_PATTERN = java.util.regex.Pattern.compile("https?://", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.Set<String> SPAM_KEYWORDS = java.util.Set.of(
            "buy now", "click here", "free money", "casino", "viagra", "lottery",
            "earn money", "make money fast", "work from home", "act now");

    private boolean isSpam(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        // Excessive URLs (>3)
        long urlCount = URL_PATTERN.matcher(content).results().count();
        if (urlCount > 3) return true;
        // Common spam keywords
        for (String keyword : SPAM_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        // Repeated characters (>10)
        if (REPEATED_CHARS.matcher(content).find()) return true;
        // Excessive length (>10000 chars)
        if (content.length() > 10000) return true;
        return false;
    }

    private CommentStatus determineCommentStatus(ContentModerationService.ModerationResult result, boolean isTrusted) {
        return switch (result.getSeverity()) {
            case HIGH -> CommentStatus.REJECTED;
            case MEDIUM -> isTrusted ? CommentStatus.APPROVED : CommentStatus.PENDING;
            case LOW, NONE -> CommentStatus.APPROVED;
        };
    }

    // ==================== OWNERSHIP ENFORCEMENT ====================

    /**
     * Get the current authenticated user from the reactive security context.
     * ARCH-3: delegates to the shared {@link CurrentUserService}.
     */
    private Mono<dev.catananti.entity.User> getCurrentUser() {
        return currentUserService.currentUser();
    }

    /**
     * Verify that the current user owns the article that a comment belongs to.
     * ADMIN can moderate any comment; DEV can only moderate comments on their own articles.
     */
    private Mono<Void> verifyCommentOwnership(Long commentId) {
        return getCurrentUser().flatMap(user -> {
            if (UserRole.ADMIN.matches(user.getRole())) {
                return Mono.empty(); // ADMIN: always allowed
            }
            return commentRepository.findById(commentId)
                    .switchIfEmpty(Mono.error(new ResourceNotFoundException("Comment", "id", commentId)))
                    .flatMap(comment -> articleRepository.findById(comment.getArticleId())
                            .flatMap(article -> {
                                if (user.getId().equals(article.getAuthorId())) {
                                    return Mono.empty(); // Own article's comment
                                }
                                return Mono.error(new AccessDeniedException(
                                        "You can only moderate comments on your own articles"));
                            }));
        });
    }
}
