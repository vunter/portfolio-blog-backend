package dev.catananti.service;

import dev.catananti.dto.CommentRequest;
import dev.catananti.dto.CommentResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.entity.Article;
import dev.catananti.entity.Comment;
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
                .status("PUBLISHED")
                .build();

        testComment = Comment.builder()
                .id(commentId)
                .articleId(articleId)
                .authorName("John Doe")
                .authorEmail("john@example.com")
                .content("Great article!")
                .status("APPROVED")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Should return approved comments for article")
    void getApprovedCommentsByArticleSlug_ShouldReturnComments() {
        // Given
        when(articleRepository.findBySlug("test-article"))
                .thenReturn(Mono.just(testArticle));
        when(commentRepository.findApprovedByArticleId(articleId))
                .thenReturn(Flux.just(testComment));
        when(commentRepository.findApprovedRepliesByParentId(commentId))
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
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();

        when(articleRepository.findBySlug("test-article"))
                .thenReturn(Mono.just(testArticle));
        when(commentRepository.save(any(Comment.class)))
                .thenReturn(Mono.just(savedComment));
        when(htmlSanitizerService.stripHtml(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        Mono<CommentResponse> result = commentService.createComment("test-article", request);

        // Then
        StepVerifier.create(result)
                .assertNext(comment -> {
                    assertThat(comment.getAuthorName()).isEqualTo("Jane Doe");
                    assertThat(comment.getStatus()).isEqualTo("PENDING");
                })
                .verifyComplete();
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
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();

        Comment approvedComment = Comment.builder()
                .id(commentId)
                .articleId(articleId)
                .authorName("John Doe")
                .authorEmail("john@example.com")
                .content("Great article!")
                .status("APPROVED")
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
                .status("REJECTED")
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
                .content("Buy stuff").status("SPAM").createdAt(LocalDateTime.now()).build();

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
        when(commentRepository.findAllByArticleId(articleId)).thenReturn(Flux.just(testComment));

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
        when(commentRepository.findApprovedByArticleIdPaginated(articleId, 10, 0))
                .thenReturn(Flux.just(testComment));
        when(commentRepository.countApprovedByArticleId(articleId)).thenReturn(Mono.just(1L));
        when(commentRepository.findApprovedRepliesByParentId(commentId)).thenReturn(Flux.empty());

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
                .status("APPROVED").createdAt(LocalDateTime.now()).build();

        CommentRequest request = CommentRequest.builder()
                .authorName("Reply Author").authorEmail("reply@example.com")
                .content("This is a reply").parentId(100L).build();

        Comment savedReply = Comment.builder()
                .id(200L).articleId(articleId).authorName("Reply Author")
                .authorEmail("reply@example.com").content("This is a reply")
                .status("PENDING").parentId(100L).createdAt(LocalDateTime.now()).build();

        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.findById(100L)).thenReturn(Mono.just(parentComment));
        when(commentRepository.save(any(Comment.class))).thenReturn(Mono.just(savedReply));
        when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));

        StepVerifier.create(commentService.createComment("test-article", request))
                .assertNext(comment -> {
                    assertThat(comment.getParentId()).isEqualTo("100");
                    assertThat(comment.getAuthorName()).isEqualTo("Reply Author");
                    assertThat(comment.getStatus()).isEqualTo("PENDING");
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
                .status("PENDING").createdAt(LocalDateTime.now()).build();

        when(articleRepository.findBySlug("test-article")).thenReturn(Mono.just(testArticle));
        when(commentRepository.save(any(Comment.class))).thenReturn(Mono.just(savedComment));
        when(htmlSanitizerService.stripHtml(anyString())).thenAnswer(inv -> inv.getArgument(0));
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
}
