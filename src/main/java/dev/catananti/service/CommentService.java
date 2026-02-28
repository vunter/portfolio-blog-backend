package dev.catananti.service;

import dev.catananti.dto.CommentRequest;
import dev.catananti.dto.CommentResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Comment;
import dev.catananti.entity.CommentStatus;
import dev.catananti.entity.UserRole;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
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
    private final IdService idService;
    private final NotificationEventService notificationEventService;
    private final BlogMetrics blogMetrics;

    // ==================== PUBLIC ENDPOINTS ====================

    public Flux<CommentResponse> getApprovedCommentsByArticleSlug(String slug) {
        return articleRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "slug", slug)))
                .flatMapMany(article -> commentRepository.findApprovedByArticleId(article.getId()))
                .flatMap(this::enrichWithReplies)
                .map(this::toPublicResponse);
    }

    public Mono<PageResponse<CommentResponse>> getApprovedCommentsByArticleSlugPaginated(String slug, int page, int size) {
        int offset = page * size;
        return articleRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "slug", slug)))
                .flatMap(article -> commentRepository.findApprovedByArticleIdPaginated(article.getId(), size, offset)
                        .flatMap(this::enrichWithReplies)
                        .map(this::toPublicResponse)
                        .collectList()
                        .zipWith(commentRepository.countApprovedByArticleId(article.getId()))
                        .map(tuple -> {
                            var content = tuple.getT1();
                            var total = tuple.getT2();
                            return PageResponse.of(content, page, size, total);
                        }));
    }

    public Mono<Long> getCommentCountByArticleSlug(String slug) {
        return articleRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "slug", slug)))
                .flatMap(article -> commentRepository.countApprovedByArticleId(article.getId()));
    }

    @Transactional
    public Mono<CommentResponse> createComment(String slug, CommentRequest request) {
        return articleRepository.findBySlug(slug)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Article", "slug", slug)))
                .flatMap(article -> {
                    // Validate parent comment if it's a reply
                    Mono<Void> parentValidation = Mono.empty();
                    if (request.getParentId() != null) {
                        parentValidation = commentRepository.findById(request.getParentId())
                                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Parent comment", "id", request.getParentId())))
                                .filter(parent -> parent.getArticleId().equals(article.getId()))
                                .switchIfEmpty(Mono.error(new IllegalArgumentException("Parent comment does not belong to this article")))
                                .then();
                    }

                    return parentValidation.then(Mono.defer(() -> {
                        // F-166: Sanitize user input to prevent XSS attacks
                        String sanitizedContent = htmlSanitizerService.stripHtml(request.getContent());
                        String sanitizedAuthorName = htmlSanitizerService.stripHtml(request.getAuthorName());

                        // F-167: Spam filter check
                        if (isSpam(sanitizedContent)) {
                            return Mono.error(new IllegalArgumentException("Comment rejected: detected as spam"));
                        }
                        
                        Comment comment = Comment.builder()
                                .id(idService.nextId())
                                .articleId(article.getId())
                                .authorName(sanitizedAuthorName)
                                .authorEmail(request.getAuthorEmail())
                                .content(sanitizedContent)
                                .status(CommentStatus.PENDING.name()) // Needs moderation
                                .parentId(request.getParentId())
                                .createdAt(LocalDateTime.now())
                                .build();

                        return commentRepository.save(comment)
                                .flatMap(savedComment -> {
                                    // Notify article author about new comment
                                    if (article.getAuthorId() != null) {
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
                                                            author.getEmail(), e.getMessage());
                                                    return Mono.empty();
                                                }))
                                                .thenReturn(savedComment);
                                    }
                                    return Mono.just(savedComment);
                                })
                                .doOnSuccess(c -> {
                                    log.info("Comment created for article {} by {} ({}): {}",
                                            slug, c.getAuthorName(), c.getAuthorEmail(), c.getId());
                                    notificationEventService.commentReceived(slug, c.getAuthorName());
                                    blogMetrics.incrementCommentCreated();
                                })
                                .map(this::toPublicResponse);
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
        return commentRepository.findAllByArticleId(articleId)
                .map(this::toResponse);
    }

    @Transactional
    public Mono<CommentResponse> approveComment(Long id) {
        return updateCommentStatus(id, CommentStatus.APPROVED.name());
    }

    @Transactional
    public Mono<CommentResponse> rejectComment(Long id) {
        return updateCommentStatus(id, CommentStatus.REJECTED.name());
    }

    @Transactional
    public Mono<CommentResponse> markAsSpam(Long id) {
        return updateCommentStatus(id, CommentStatus.SPAM.name());
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
     * ADMIN sees all comments; DEV/EDITOR see only comments on their own articles.
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
                // DEV/EDITOR: only comments on own articles
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
     * ADMIN sees all; DEV/EDITOR only see comments on articles they authored.
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

    private Mono<CommentResponse> updateCommentStatus(Long id, String status) {
        return commentRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Comment", "id", id)))
                .flatMap(comment -> {
                    comment.setStatus(status);
                    return commentRepository.save(comment);
                })
                .doOnSuccess(c -> {
                    log.info("Comment {} status updated to: {}", id, status);
                    if (CommentStatus.APPROVED.matches(status)) {
                        notificationEventService.commentApproved(id);
                    }
                })
                .map(this::toResponse);
    }

    private Mono<Comment> enrichWithReplies(Comment comment) {
        return commentRepository.findApprovedRepliesByParentId(comment.getId())
                .collectList()
                .doOnNext(comment::setReplies)
                .thenReturn(comment);
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
                .status(comment.getStatus())
                .parentId(comment.getParentId() != null ? String.valueOf(comment.getParentId()) : null)
                .replies(comment.getReplies() != null ? 
                        comment.getReplies().stream().map(this::toPublicResponse).toList() : 
                        Collections.emptyList())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getCreatedAt())
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
                .status(comment.getStatus())
                .parentId(comment.getParentId() != null ? String.valueOf(comment.getParentId()) : null)
                .replies(comment.getReplies() != null ? 
                        comment.getReplies().stream().map(this::toResponse).toList() : 
                        Collections.emptyList())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getCreatedAt()) // Use createdAt as fallback for updatedAt
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

    // ==================== OWNERSHIP ENFORCEMENT ====================

    /**
     * Get the current authenticated user from the reactive security context.
     */
    private Mono<dev.catananti.entity.User> getCurrentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> auth.getName())
                .flatMap(email -> userRepository.findByEmail(email));
    }

    /**
     * Verify that the current user owns the article that a comment belongs to.
     * ADMIN can moderate any comment; DEV/EDITOR can only moderate comments on their own articles.
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
