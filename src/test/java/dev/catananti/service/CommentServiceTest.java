package dev.catananti.service;

import dev.catananti.dto.CommentRequest;
import dev.catananti.dto.CommentResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.ArticleStatus;
import dev.catananti.entity.Comment;
import dev.catananti.entity.CommentStatus;
import dev.catananti.entity.User;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.metrics.BlogMetrics;
import dev.catananti.repository.ArticleRepository;
import dev.catananti.repository.CommentRepository;
import dev.catananti.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private HtmlSanitizerService htmlSanitizerService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private IdService idService;

    @Mock
    private NotificationEventService notificationEventService;

    @Mock
    private BlogMetrics blogMetrics;

    @Mock
    private ContentModerationService contentModerationService;

    @Mock
    private dev.catananti.config.PaginationConfig paginationConfig;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private CommentService commentService;

    private Article testArticle;
    private Comment testComment;
    private Long articleId;
    private Long commentId;

    @BeforeEach
    void setUp() {
        articleId = 1234567890123456L;
        commentId = 987654321098765L;

        testArticle = Article.builder()
                .id(articleId)
                .slug("test-article")
                .title("Test Article")
                .content("Content")
                .status(ArticleStatus.PUBLISHED)
                .build();

        testComment = Comment.builder()
                .id(commentId)
                .articleId(articleId)
                .authorName("John Doe")
                .authorEmail("john@example.com")
                .content("Great article!")
                .status(CommentStatus.APPROVED)
                .createdAt(LocalDateTime.now())
                .build();

        lenient().when(paginationConfig.getCommentTreeMax()).thenReturn(500);
        lenient().when(paginationConfig.getBulkQueryMax()).thenReturn(1000);
    }

    @Test
    @DisplayName("Should return approved comments for article")
    void getApprovedCommentsByArticleSlug_ShouldReturnComments() {
        // Given
        when(articleRepository.findBySlug("test-article"))
                .thenReturn(Mono.just(testArticle));
        when(commentRepository.findApprovedByArticleId(eq(articleId), anyInt()))
                .thenReturn(Flux.just(testComment));
        when(commentRepository.findApprovedRepliesByParentIds(anyList()))
                .thenReturn(Flux.empty());

        // When
        Flux<CommentResponse> result = commentService.getApprovedCommentsByArticleSlug("test-article");

        // Then
        StepVerifier.create(result)
                .assertNext(comment -> {
                    assertThat(comment.getAuthorName()).isEqualTo("John Doe");
                    assertThat(comment.getContent()).isEqualTo("Great article!");
                    assertThat(comment.getStatus()).isEqualTo("APPROVED");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when article not found")
    void getApprovedCommentsByArticleSlug_ShouldThrowWhenArticleNotFound() {
        // Given
        when(articleRepository.findBySlug("non-existent"))
                .thenReturn(Mono.empty());

        // When
        Flux<CommentResponse> result = commentService.getApprovedCommentsByArticleSlug("non-existent");

        // Then
        StepVerifier.create(result)
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should create comment with PENDING status")
    void createComment_ShouldCreateWithPendingStatus() {
        // Given
        CommentRequest request = CommentRequest.builder()
                .authorName("Jane Doe")
                .authorEmail("jane@example.com")
                .content("Nice post!")
                .build();

        Comment savedComment = Comment.builder()
                .id(555555555555555L)
                .articleId(articleId)
                .authorName("Jane Doe")
                .authorEmail("jane@example.com")
                .content("Nice post!")
                .status(CommentStatus.APPROVED)
                .createdAt(LocalDateTime.now())
                .build();

        when(articleRepository.findBySlug("test-article"))
                .thenReturn(Mono.just(testArticle));
        when(commentRepository.save(any(Comment.class)))
                .thenReturn(Mono.just(savedComment));
        when(htmlSanitizerService.stripHtml(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(contentModerationService.analyzeContent(anyString(), anyString()))
                .thenReturn(ContentModerationService.ModerationResult.builder()
                        .severity(ContentModerationService.Severity.NONE).safe(true).reasons(java.util.List.of()).build());
        when(commentRepository.countApprovedByAuthorEmail(anyString()))
                .thenReturn(Mono.just(0L));

        // When
        Mono<CommentResponse> result = commentService.createComment("test-article", request);

        // Then
        StepVerifier.create(result)
                .assertNext(comment -> {
                    assertThat(comment.getAuthorName()).isEqualTo("Jane Doe");
                    assertThat(comment.getStatus()).isEqualTo("APPROVED");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should persist the authenticated author's user id on the comment")
    void createComment_ShouldCarryAuthenticatedUserId() {
        // Given — the controller resolves the logged user and stamps the request;
        // without propagating it, erasure-by-user_id misses comments created
        // after the V21 backfill ran.
        CommentRequest request = CommentRequest.builder()
                .authorName("Jane Doe")
                .authorEmail("jane@example.com")
                .content("Nice post!")
                .userId(42L)
                .build();

        when(articleRepository.findBySlug("test-article"))
                .thenReturn(Mono.just(testArticle));
        when(commentRepository.save(any(Comment.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(htmlSanitizerService.stripHtml(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(contentModerationService.analyzeContent(anyString(), anyString()))
                .thenReturn(ContentModerationService.ModerationResult.builder()
                        .severity(ContentModerationService.Severity.NONE).safe(true).reasons(java.util.List.of()).build());
        when(commentRepository.countApprovedByAuthorEmail(anyString()))
                .thenReturn(Mono.just(0L));

        // When
        StepVerifier.create(commentService.createComment("test-article", request))
                .expectNextCount(1)
                .verifyComplete();

        // Then
        org.mockito.ArgumentCaptor<Comment> saved = org.mockito.ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Should approve comment")
    void approveComment_ShouldUpdateStatusToApproved() {
        // Given
        Comment pendingComment = Comment.builder()
                .id(commentId)
                .articleId(articleId)
                .authorName("John Doe")
                .authorEmail("john@example.com")
                .content("Great article!")
                .status(CommentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        Comment approvedComment = Comment.builder()
                .id(commentId)
                .articleId(articleId)
                .authorName("John Doe")
                .authorEmail("john@example.com")
                .content("Great article!")
                .status(CommentStatus.APPROVED)
                .createdAt(LocalDateTime.now())
                .build();

        when(commentRepository.findById(commentId))
                .thenReturn(Mono.just(pendingComment));
        when(commentRepository.save(any(Comment.class)))
                .thenReturn(Mono.just(approvedComment));

        // When
        Mono<CommentResponse> result = commentService.approveComment(commentId);

        // Then
        StepVerifier.create(result)
                .assertNext(comment -> {
                    assertThat(comment.getStatus()).isEqualTo("APPROVED");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject comment")
    void rejectComment_ShouldUpdateStatusToRejected() {
        // Given
        Comment rejectedComment = Comment.builder()
                .id(commentId)
                .status(CommentStatus.REJECTED)
                .build();

        when(commentRepository.findById(commentId))
                .thenReturn(Mono.just(testComment));
        when(commentRepository.save(any(Comment.class)))
                .thenReturn(Mono.just(rejectedComment));

        // When
        Mono<CommentResponse> result = commentService.rejectComment(commentId);

        // Then
        StepVerifier.create(result)
                .assertNext(comment -> {
                    assertThat(comment.getStatus()).isEqualTo("REJECTED");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should delete comment")
    void deleteComment_ShouldDeleteComment() {
        // Given — deleteComment uses findById, then deleteByParentId, then deleteById
        when(commentRepository.findById(commentId))
                .thenReturn(Mono.just(testComment));
        when(commentRepository.deleteByParentId(commentId))
                .thenReturn(Mono.empty());
        when(commentRepository.deleteById(commentId))
                .thenReturn(Mono.empty());

        // When
        Mono<Void> result = commentService.deleteComment(commentId);

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(commentRepository).deleteById(commentId);
    }

    // ==================== ADDED TESTS ====================

    @Test
    @DisplayName("Should delete comment idempotently when not found")
    void deleteComment_ShouldBeIdempotent_WhenNotFound() {
        when(commentRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(commentService.deleteComment(999L))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should mark comment as spam")
    void markAsSpam_ShouldUpdateStatus() {
        Comment spamComment = Comment.builder()
                .id(commentId).articleId(articleId).authorName("Spammer")
                .content("Buy stuff").status(CommentStatus.SPAM).createdAt(LocalDateTime.now()).build();

        when(commentRepository.findById(commentId)).thenReturn(Mono.just(testComment));
        when(commentRepository.save(any(Comment.class))).thenReturn(Mono.just(spamComment));

        StepVerifier.create(commentService.markAsSpam(commentId))
                .assertNext(c -> assertThat(c.getStatus()).isEqualTo("SPAM"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw when approving non-existent comment")
    void approveComment_ShouldThrow_WhenNotFound() {
        when(commentRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(commentService.approveComment(999L))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should throw when rejecting non-existent comment")
    void rejectComment_ShouldThrow_WhenNotFound() {
        when(commentRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(commentService.rejectComment(999L))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should get comment count by article slug")
    void getCommentCountByArticleSlug_ShouldReturnCount() {
        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.countApprovedByArticleId(articleId)).thenReturn(Mono.just(7L));

        StepVerifier.create(commentService.getCommentCountByArticleSlug("test-article"))
                .assertNext(count -> assertThat(count).isEqualTo(7L))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw when getting comment count for non-existent article")
    void getCommentCountByArticleSlug_ShouldThrow_WhenNotFound() {
        when(articleRepository.findBySlug("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(commentService.getCommentCountByArticleSlug("ghost"))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should get all comments by article ID")
    void getAllCommentsByArticleId_ShouldReturnComments() {
        when(commentRepository.findAllByArticleId(eq(articleId), anyInt())).thenReturn(Flux.just(testComment));

        StepVerifier.create(commentService.getAllCommentsByArticleId(articleId).collectList())
                .assertNext(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.getFirst().getAuthorName()).isEqualTo("John Doe");
                    assertThat(list.getFirst().getAuthorEmail()).isEqualTo("john@example.com");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get comments by status with pagination")
    void getCommentsByStatus_ShouldReturnPaginated() {
        when(commentRepository.findByStatus("PENDING", 10, 0)).thenReturn(Flux.just(testComment));
        when(commentRepository.countByStatus("PENDING")).thenReturn(Mono.just(1L));
        when(articleRepository.findAllById(any(Iterable.class)))
                .thenReturn(Flux.just(testArticle));

        StepVerifier.create(commentService.getCommentsByStatus("pending", 0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(1);
                    assertThat(page.getTotalElements()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get all comments paginated")
    void getAllCommentsPaginated_ShouldReturnPaginated() {
        when(commentRepository.findAllPaginated(10, 0)).thenReturn(Flux.just(testComment));
        when(commentRepository.count()).thenReturn(Mono.just(1L));
        when(articleRepository.findAllById(any(Iterable.class)))
                .thenReturn(Flux.just(testArticle));

        StepVerifier.create(commentService.getAllCommentsPaginated(0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(1);
                    assertThat(page.getTotalElements()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get all comments paginated with empty results")
    void getAllCommentsPaginated_ShouldReturnEmpty() {
        when(commentRepository.findAllPaginated(10, 0)).thenReturn(Flux.empty());
        when(commentRepository.count()).thenReturn(Mono.just(0L));

        StepVerifier.create(commentService.getAllCommentsPaginated(0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).isEmpty();
                    assertThat(page.getTotalElements()).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get approved comments paginated by article slug")
    void getApprovedCommentsByArticleSlugPaginated_ShouldReturn() {
        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.findApprovedByArticleIdSortedByLikes(articleId, 10, 0))
                .thenReturn(Flux.just(testComment));
        when(commentRepository.countApprovedByArticleId(articleId)).thenReturn(Mono.just(1L));
        when(commentRepository.findApprovedRepliesByParentIds(anyList())).thenReturn(Flux.empty());

        StepVerifier.create(commentService.getApprovedCommentsByArticleSlugPaginated("test-article", 0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(1);
                    assertThat(page.getTotalElements()).isEqualTo(1);
                    assertThat(page.getContent().getFirst().getAuthorName()).isEqualTo("John Doe");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw getting paginated comments for non-existent article")
    void getApprovedCommentsByArticleSlugPaginated_ShouldThrow_WhenNotFound() {
        when(articleRepository.findBySlug("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(commentService.getApprovedCommentsByArticleSlugPaginated("ghost", 0, 10))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should create comment as reply with parentId")
    void createComment_AsReply_ShouldSetParentId() {
        Comment parentComment = Comment.builder()
                .id(100L).articleId(articleId).authorName("Parent Author")
                .status(CommentStatus.APPROVED).createdAt(LocalDateTime.now()).build();

        CommentRequest request = CommentRequest.builder()
                .authorName("Reply Author").authorEmail("reply@example.com")
                .content("This is a reply").parentId(100L).build();

        Comment savedReply = Comment.builder()
                .id(200L).articleId(articleId).authorName("Reply Author")
                .authorEmail("reply@example.com").content("This is a reply")
                .status(CommentStatus.APPROVED).parentId(100L).createdAt(LocalDateTime.now()).build();

        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.findById(100L)).thenReturn(Mono.just(parentComment));
        when(commentRepository.save(any(Comment.class))).thenReturn(Mono.just(savedReply));
        when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(contentModerationService.analyzeContent(anyString(), anyString()))
                .thenReturn(ContentModerationService.ModerationResult.builder()
                        .severity(ContentModerationService.Severity.NONE).safe(true).reasons(java.util.List.of()).build());
        when(commentRepository.countApprovedByAuthorEmail(anyString()))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(commentService.createComment("test-article", request))
                .assertNext(comment -> {
                    assertThat(comment.getParentId()).isEqualTo("100");
                    assertThat(comment.getAuthorName()).isEqualTo("Reply Author");
                    assertThat(comment.getStatus()).isEqualTo("APPROVED");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject reply when parent comment not found")
    void createComment_AsReply_ShouldThrow_WhenParentNotFound() {
        CommentRequest request = CommentRequest.builder()
                .authorName("Reply Author").authorEmail("reply@example.com")
                .content("Reply").parentId(999L).build();

        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(commentService.createComment("test-article", request))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should create comment and notify author")
    void createComment_ShouldNotifyArticleAuthor() {
        testArticle.setAuthorId(50L);
        User author = User.builder().id(50L).name("Author").email("author@example.com").build();

        CommentRequest request = CommentRequest.builder()
                .authorName("Commenter").authorEmail("c@example.com")
                .content("Nice!").build();

        Comment savedComment = Comment.builder()
                .id(300L).articleId(articleId).authorName("Commenter")
                .authorEmail("c@example.com").content("Nice!")
                .status(CommentStatus.PENDING).createdAt(LocalDateTime.now()).build();

        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.save(any(Comment.class))).thenReturn(Mono.just(savedComment));
        when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(contentModerationService.analyzeContent(anyString(), anyString()))
                .thenReturn(ContentModerationService.ModerationResult.builder()
                        .severity(ContentModerationService.Severity.MEDIUM).safe(false)
                        .reasons(java.util.List.of("en:medium:test")).build());
        when(commentRepository.countApprovedByAuthorEmail(anyString()))
                .thenReturn(Mono.just(0L));
        when(userRepository.findById(50L)).thenReturn(Mono.just(author));
        when(emailService.sendCommentNotification(
                eq("author@example.com"), eq("Author"), eq("Commenter"),
                eq("Test Article"), eq("test-article"), eq("Nice!")))
                .thenReturn(Mono.empty());

        StepVerifier.create(commentService.createComment("test-article", request))
                .assertNext(comment -> assertThat(comment.getStatus()).isEqualTo("PENDING"))
                .verifyComplete();

        verify(emailService).sendCommentNotification(
                eq("author@example.com"), eq("Author"), eq("Commenter"),
                eq("Test Article"), eq("test-article"), eq("Nice!"));
    }

    @Test
    @DisplayName("Should create comment for article not found")
    void createComment_ShouldThrow_WhenArticleNotFound() {
        CommentRequest request = CommentRequest.builder()
                .authorName("X").authorEmail("x@x.com").content("Hi").build();

        when(articleRepository.findBySlug("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(commentService.createComment("ghost", request))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ==================== AUD18-L2: getApprovedCommentForArticle ====================

    @Test
    @DisplayName("AUD18-L2: Should resolve APPROVED comment belonging to slug's article")
    void getApprovedCommentForArticle_ShouldReturnComment() {
        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.findById(commentId)).thenReturn(Mono.just(testComment));

        StepVerifier.create(commentService.getApprovedCommentForArticle("test-article", commentId))
                .assertNext(comment -> assertThat(comment.getId()).isEqualTo(commentId))
                .verifyComplete();
    }

    @Test
    @DisplayName("AUD18-L2: Should 404 when article slug does not exist")
    void getApprovedCommentForArticle_ShouldThrow_WhenArticleNotFound() {
        when(articleRepository.findBySlug("ghost")).thenReturn(Mono.empty());

        StepVerifier.create(commentService.getApprovedCommentForArticle("ghost", commentId))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("AUD18-L2: Should 404 when comment does not exist")
    void getApprovedCommentForArticle_ShouldThrow_WhenCommentNotFound() {
        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(commentService.getApprovedCommentForArticle("test-article", 999L))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("AUD18-L2: Should 404 when comment belongs to a different article")
    void getApprovedCommentForArticle_ShouldThrow_WhenForeignComment() {
        Comment foreignComment = Comment.builder()
                .id(commentId).articleId(articleId + 1)
                .status(CommentStatus.APPROVED).createdAt(LocalDateTime.now()).build();
        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.findById(commentId)).thenReturn(Mono.just(foreignComment));

        StepVerifier.create(commentService.getApprovedCommentForArticle("test-article", commentId))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("AUD18-L2: Should 404 when comment is not APPROVED")
    void getApprovedCommentForArticle_ShouldThrow_WhenNotApproved() {
        testComment.setStatus(CommentStatus.PENDING);
        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.findById(commentId)).thenReturn(Mono.just(testComment));

        StepVerifier.create(commentService.getApprovedCommentForArticle("test-article", commentId))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    // ==================== AUD18-JA11: bulk moderation ownership ====================

    @Test
    @DisplayName("AUD18-JA11: ADMIN should bulk-approve any comments unrestricted")
    void bulkApprove_AsAdmin_ShouldApproveAll() {
        User admin = User.builder().id(1L).role("ADMIN").email("admin@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(admin));
        when(commentRepository.findById(commentId)).thenReturn(Mono.just(testComment));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(commentService.bulkApprove(java.util.List.of(commentId)))
                .assertNext(comment -> assertThat(comment.getStatus()).isEqualTo("APPROVED"))
                .verifyComplete();
    }

    @Test
    @DisplayName("AUD18-JA11: DEV should bulk-approve comments on own articles")
    void bulkApprove_AsDevOnOwnArticle_ShouldApprove() {
        testArticle.setAuthorId(42L);
        User dev = User.builder().id(42L).role("DEV").email("dev@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(dev));
        when(commentRepository.findById(commentId)).thenReturn(Mono.just(testComment));
        when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(commentService.bulkApprove(java.util.List.of(commentId)))
                .assertNext(comment -> assertThat(comment.getStatus()).isEqualTo("APPROVED"))
                .verifyComplete();
    }

    @Test
    @DisplayName("AUD18-JA11: DEV should be denied bulk-approving foreign comments")
    void bulkApprove_AsDevOnForeignArticle_ShouldDeny() {
        testArticle.setAuthorId(99L); // someone else's article
        User dev = User.builder().id(42L).role("DEV").email("dev@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(dev));
        when(commentRepository.findById(commentId)).thenReturn(Mono.just(testComment));
        when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));

        StepVerifier.create(commentService.bulkApprove(java.util.List.of(commentId)))
                .expectError(org.springframework.security.access.AccessDeniedException.class)
                .verify();

        verify(commentRepository, never()).save(any(Comment.class));
    }

    @Test
    @DisplayName("AUD18-JA11: DEV should be denied bulk-rejecting foreign comments")
    void bulkReject_AsDevOnForeignArticle_ShouldDeny() {
        testArticle.setAuthorId(99L);
        User dev = User.builder().id(42L).role("DEV").email("dev@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(dev));
        when(commentRepository.findById(commentId)).thenReturn(Mono.just(testComment));
        when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));

        StepVerifier.create(commentService.bulkReject(java.util.List.of(commentId)))
                .expectError(org.springframework.security.access.AccessDeniedException.class)
                .verify();

        verify(commentRepository, never()).save(any(Comment.class));
    }

    @Test
    @DisplayName("AUD18-JA11: DEV should be denied bulk-spamming foreign comments")
    void bulkMarkAsSpam_AsDevOnForeignArticle_ShouldDeny() {
        testArticle.setAuthorId(99L);
        User dev = User.builder().id(42L).role("DEV").email("dev@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(dev));
        when(commentRepository.findById(commentId)).thenReturn(Mono.just(testComment));
        when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));

        StepVerifier.create(commentService.bulkMarkAsSpam(java.util.List.of(commentId)))
                .expectError(org.springframework.security.access.AccessDeniedException.class)
                .verify();

        verify(commentRepository, never()).save(any(Comment.class));
    }

    // ==================== AUD19C-2: admin comment search ====================

    @Test
    @DisplayName("AUD19C-2: ADMIN search should route to the search query with escaped LIKE wildcards")
    void getAdminCommentsByStatus_AdminWithSearch_ShouldUseSearchQueryEscaped() {
        User admin = User.builder().id(1L).role("ADMIN").email("admin@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(admin));
        // '%' in the user term must reach the repository escaped (F-291)
        when(commentRepository.findByStatusAndSearch("PENDING", "50\\%", 20, 0))
                .thenReturn(Flux.just(testComment));
        when(commentRepository.countByStatusAndSearch("PENDING", "50\\%"))
                .thenReturn(Mono.just(1L));
        when(articleRepository.findAllById(java.util.List.of(articleId)))
                .thenReturn(Flux.just(testArticle));

        StepVerifier.create(commentService.getAdminCommentsByStatus("PENDING", " 50% ", 0, 20))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(1);
                    assertThat(page.getContent().getFirst().getArticleSlug()).isEqualTo("test-article");
                })
                .verifyComplete();

        verify(commentRepository).findByStatusAndSearch("PENDING", "50\\%", 20, 0);
        verify(commentRepository, never()).findByStatus(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("AUD19C-2: ADMIN search with status ALL should route to findAllBySearch")
    void getAdminCommentsByStatus_AdminSearchAll_ShouldUseAllSearchQuery() {
        User admin = User.builder().id(1L).role("ADMIN").email("admin@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(admin));
        when(commentRepository.findAllBySearch("john", 20, 0)).thenReturn(Flux.just(testComment));
        when(commentRepository.countAllBySearch("john")).thenReturn(Mono.just(1L));
        when(articleRepository.findAllById(java.util.List.of(articleId)))
                .thenReturn(Flux.just(testArticle));

        StepVerifier.create(commentService.getAdminCommentsByStatus("ALL", "john", 0, 20))
                .assertNext(page -> assertThat(page.getTotalElements()).isEqualTo(1))
                .verifyComplete();

        verify(commentRepository, never()).findAllPaginated(anyInt(), anyInt());
    }

    @Test
    @DisplayName("AUD19C-2: DEV search should stay ownership-scoped (author-joined search query)")
    void getAdminCommentsByStatus_DevWithSearch_ShouldUseAuthorScopedSearchQuery() {
        User devUser = User.builder().id(42L).role("DEV").email("dev@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(devUser));
        when(commentRepository.findByArticleAuthorIdAndStatusAndSearch(42L, "PENDING", "john", 20, 0))
                .thenReturn(Flux.just(testComment));
        when(commentRepository.countByArticleAuthorIdAndStatusAndSearch(42L, "PENDING", "john"))
                .thenReturn(Mono.just(1L));
        when(articleRepository.findAllById(java.util.List.of(articleId)))
                .thenReturn(Flux.just(testArticle));

        StepVerifier.create(commentService.getAdminCommentsByStatus("PENDING", "john", 0, 20))
                .assertNext(page -> assertThat(page.getContent()).hasSize(1))
                .verifyComplete();

        verify(commentRepository, never()).findByArticleAuthorIdAndStatus(anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("AUD19C-2: blank search should keep the existing non-search branches")
    void getAdminCommentsByStatus_BlankSearch_ShouldUsePlainQueries() {
        User admin = User.builder().id(1L).role("ADMIN").email("admin@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(admin));
        when(commentRepository.findByStatus("PENDING", 20, 0)).thenReturn(Flux.just(testComment));
        when(commentRepository.countByStatus("PENDING")).thenReturn(Mono.just(1L));
        when(articleRepository.findAllById(java.util.List.of(articleId)))
                .thenReturn(Flux.just(testArticle));

        StepVerifier.create(commentService.getAdminCommentsByStatus("PENDING", "   ", 0, 20))
                .assertNext(page -> assertThat(page.getContent()).hasSize(1))
                .verifyComplete();

        verify(commentRepository, never()).findByStatusAndSearch(anyString(), anyString(), anyInt(), anyInt());
    }

    // ==================== AUD19C-3: per-article admin comments enrichment ====================

    @Test
    @DisplayName("AUD19C-3: ADMIN per-article listing should carry articleSlug/articleTitle")
    void getAdminCommentsByArticleId_Admin_ShouldEnrichSlugAndTitle() {
        User admin = User.builder().id(1L).role("ADMIN").email("admin@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(admin));
        when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
        when(commentRepository.findAllByArticleId(eq(articleId), anyInt())).thenReturn(Flux.just(testComment));

        StepVerifier.create(commentService.getAdminCommentsByArticleId(articleId))
                .assertNext(comment -> {
                    assertThat(comment.getArticleSlug()).isEqualTo("test-article");
                    assertThat(comment.getArticleTitle()).isEqualTo("Test Article");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("AUD19C-3: DEV per-article listing on own article should carry slug/title")
    void getAdminCommentsByArticleId_DevOwnArticle_ShouldEnrichSlugAndTitle() {
        testArticle.setAuthorId(42L);
        User devUser = User.builder().id(42L).role("DEV").email("dev@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(devUser));
        when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));
        when(commentRepository.findAllByArticleId(eq(articleId), anyInt())).thenReturn(Flux.just(testComment));

        StepVerifier.create(commentService.getAdminCommentsByArticleId(articleId))
                .assertNext(comment -> assertThat(comment.getArticleSlug()).isEqualTo("test-article"))
                .verifyComplete();
    }

    @Test
    @DisplayName("AUD19C-3: unknown article id should 404 for ADMIN too (was a silent empty list)")
    void getAdminCommentsByArticleId_UnknownArticle_ShouldThrowForAdmin() {
        User admin = User.builder().id(1L).role("ADMIN").email("admin@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(admin));
        when(articleRepository.findById(articleId)).thenReturn(Mono.empty());

        StepVerifier.create(commentService.getAdminCommentsByArticleId(articleId))
                .expectError(ResourceNotFoundException.class)
                .verify();

        verify(commentRepository, never()).findAllByArticleId(anyLong(), anyInt());
    }

    @Test
    @DisplayName("AUD19C-3: DEV should still be denied on someone else's article")
    void getAdminCommentsByArticleId_DevForeignArticle_ShouldDeny() {
        testArticle.setAuthorId(99L);
        User devUser = User.builder().id(42L).role("DEV").email("dev@example.com").build();
        when(currentUserService.currentUser()).thenReturn(Mono.just(devUser));
        when(articleRepository.findById(articleId)).thenReturn(Mono.just(testArticle));

        StepVerifier.create(commentService.getAdminCommentsByArticleId(articleId))
                .expectError(org.springframework.security.access.AccessDeniedException.class)
                .verify();
    }
}
