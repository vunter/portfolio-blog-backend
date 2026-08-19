package dev.catananti.controller;

import dev.catananti.config.PaginationConfig;
import dev.catananti.dto.CommentRequest;
import dev.catananti.dto.CommentResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.dto.UserResponse;
import dev.catananti.service.CommentService;
import dev.catananti.service.RecaptchaService;
import dev.catananti.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentControllerTest {

    @Mock
    private CommentService commentService;

    @Mock
    private RecaptchaService recaptchaService;

    @Mock
    private UserService userService;

    @Spy
    private PaginationConfig paginationConfig = new PaginationConfig();

    @InjectMocks
    private CommentController controller;

    private CommentResponse commentResponse;
    private CommentRequest commentRequest;

    @BeforeEach
    void setUp() {
        commentResponse = CommentResponse.builder()
                .id("101")
                .articleSlug("spring-boot-guide")
                .authorName("John Doe")
                .content("Great article!")
                .status("APPROVED")
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        commentRequest = CommentRequest.builder()
                .authorName("John Doe")
                .authorEmail("john@example.com")
                .content("Great article!")
                .recaptchaToken("valid-token")
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/articles/{slug}/comments")
    class GetComments {

        @Test
        @DisplayName("Should return paginated approved comments")
        void shouldReturnPaginatedComments() {
            PageResponse<CommentResponse> page = PageResponse.<CommentResponse>builder()
                    .content(List.of(commentResponse))
                    .page(0)
                    .size(20)
                    .totalElements(1)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();

            when(commentService.getApprovedCommentsByArticleSlugPaginated("spring-boot-guide", 0, 20, "liked"))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getComments("spring-boot-guide", 0, 20, "liked"))
                    .assertNext(result -> {
                        assertThat(result.getContent()).hasSize(1);
                        assertThat(result.getContent().getFirst().getAuthorName()).isEqualTo("John Doe");
                        assertThat(result.getTotalElements()).isEqualTo(1);
                        assertThat(result.isFirst()).isTrue();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/articles/{slug}/comments/count")
    class GetCommentCount {

        @Test
        @DisplayName("Should return comment count for article")
        void shouldReturnCommentCount() {
            when(commentService.getCommentCountByArticleSlug("spring-boot-guide"))
                    .thenReturn(Mono.just(42L));

            StepVerifier.create(controller.getCommentCount("spring-boot-guide"))
                    .assertNext(count -> assertThat(count).isEqualTo(42L))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/articles/{slug}/comments")
    class CreateComment {

        @Test
        @DisplayName("Should create comment after recaptcha verification")
        void shouldCreateCommentWithRecaptcha() {
            CommentResponse created = CommentResponse.builder()
                    .id("102")
                    .articleSlug("spring-boot-guide")
                    .authorName("John Doe")
                    .content("Great article!")
                    .status("PENDING")
                    .createdAt(LocalDateTime.now())
                    .build();

            UserResponse user = UserResponse.builder()
                    .name("John Doe")
                    .username("johndoe")
                    .email("john@example.com")
                    .build();

            when(userService.getUserByEmail("john@example.com"))
                    .thenReturn(Mono.just(user));
            when(recaptchaService.verify("valid-token", "comment"))
                    .thenReturn(Mono.empty());
            when(commentService.createComment("spring-boot-guide", commentRequest))
                    .thenReturn(Mono.just(created));

            StepVerifier.create(controller.createComment("spring-boot-guide", commentRequest, "john@example.com"))
                    .assertNext(result -> {
                        assertThat(result.getId()).isEqualTo("102");
                        assertThat(result.getStatus()).isEqualTo("PENDING");
                        assertThat(result.getAuthorName()).isEqualTo("John Doe");
                    })
                    .verifyComplete();

            verify(userService).getUserByEmail("john@example.com");
            verify(recaptchaService).verify("valid-token", "comment");
            verify(commentService).createComment("spring-boot-guide", commentRequest);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/articles/{slug}/comments/{commentId}/like — AUD18-M1/AUD18-L2")
    class ToggleCommentLike {

        private static final String SLUG = "spring-boot-guide";
        private static final Long COMMENT_ID = 101L;

        @Mock
        private dev.catananti.service.InteractionDeduplicationService deduplicationService;

        @Mock
        private org.springframework.core.env.Environment environment;

        @Mock
        private org.springframework.http.server.reactive.ServerHttpRequest mockRequest;

        private CommentController likeController;

        private final dev.catananti.entity.Comment approvedComment = dev.catananti.entity.Comment.builder()
                .id(COMMENT_ID)
                .articleId(1L)
                .status(dev.catananti.entity.CommentStatus.APPROVED)
                .build();

        @BeforeEach
        void setUpController() {
            // F-065: manual construction — deduplicationService is Optional<>
            likeController = new CommentController(commentService, recaptchaService, userService,
                    java.util.Optional.of(deduplicationService), paginationConfig, environment);
        }

        @Test
        @DisplayName("AUD18-M1: new like increments when dedup key is genuinely new")
        void toggleLike_NewLike_ShouldIncrement() {
            when(commentService.getApprovedCommentForArticle(SLUG, COMMENT_ID)).thenReturn(Mono.just(approvedComment));
            when(deduplicationService.hasLikedComment(eq(COMMENT_ID), any())).thenReturn(Mono.just(false));
            when(deduplicationService.recordCommentLikeIfNew(eq(COMMENT_ID), any())).thenReturn(Mono.just(true));
            when(commentService.likeCommentAndReturnCount(COMMENT_ID)).thenReturn(Mono.just(6));

            StepVerifier.create(likeController.toggleCommentLike(SLUG, COMMENT_ID, mockRequest))
                    .assertNext(result -> {
                        assertThat(result.get("liked")).isEqualTo(true);
                        assertThat(result.get("likesCount")).isEqualTo(6);
                    })
                    .verifyComplete();

            verify(commentService).likeCommentAndReturnCount(COMMENT_ID);
            verify(commentService, never()).getCommentLikeCount(anyLong());
        }

        @Test
        @DisplayName("AUD18-M1: duplicate like does NOT increment when SETNX reports no change")
        void toggleLike_DuplicateLike_ShouldNotIncrement() {
            when(commentService.getApprovedCommentForArticle(SLUG, COMMENT_ID)).thenReturn(Mono.just(approvedComment));
            when(deduplicationService.hasLikedComment(eq(COMMENT_ID), any())).thenReturn(Mono.just(false));
            // racing duplicate: hasLiked saw false but SETNX lost the race
            when(deduplicationService.recordCommentLikeIfNew(eq(COMMENT_ID), any())).thenReturn(Mono.just(false));
            when(commentService.getCommentLikeCount(COMMENT_ID)).thenReturn(Mono.just(5));

            StepVerifier.create(likeController.toggleCommentLike(SLUG, COMMENT_ID, mockRequest))
                    .assertNext(result -> {
                        assertThat(result.get("liked")).isEqualTo(true);
                        assertThat(result.get("likesCount")).isEqualTo(5);
                    })
                    .verifyComplete();

            verify(commentService, never()).likeCommentAndReturnCount(anyLong());
        }

        @Test
        @DisplayName("AUD18-M1: unlike decrements when the dedup key was actually removed")
        void toggleLike_Unlike_ShouldDecrement() {
            when(commentService.getApprovedCommentForArticle(SLUG, COMMENT_ID)).thenReturn(Mono.just(approvedComment));
            when(deduplicationService.hasLikedComment(eq(COMMENT_ID), any())).thenReturn(Mono.just(true));
            when(deduplicationService.removeCommentLike(eq(COMMENT_ID), any())).thenReturn(Mono.just(true));
            when(commentService.unlikeCommentAndReturnCount(COMMENT_ID)).thenReturn(Mono.just(4));

            StepVerifier.create(likeController.toggleCommentLike(SLUG, COMMENT_ID, mockRequest))
                    .assertNext(result -> {
                        assertThat(result.get("liked")).isEqualTo(false);
                        assertThat(result.get("likesCount")).isEqualTo(4);
                    })
                    .verifyComplete();

            verify(commentService).unlikeCommentAndReturnCount(COMMENT_ID);
        }

        @Test
        @DisplayName("AUD18-M1: racing unlike does NOT decrement when delete removed nothing")
        void toggleLike_RacingUnlike_ShouldNotDecrement() {
            when(commentService.getApprovedCommentForArticle(SLUG, COMMENT_ID)).thenReturn(Mono.just(approvedComment));
            when(deduplicationService.hasLikedComment(eq(COMMENT_ID), any())).thenReturn(Mono.just(true));
            // racing duplicate unlike: another request already deleted the key
            when(deduplicationService.removeCommentLike(eq(COMMENT_ID), any())).thenReturn(Mono.just(false));
            when(commentService.getCommentLikeCount(COMMENT_ID)).thenReturn(Mono.just(5));

            StepVerifier.create(likeController.toggleCommentLike(SLUG, COMMENT_ID, mockRequest))
                    .assertNext(result -> {
                        assertThat(result.get("liked")).isEqualTo(false);
                        assertThat(result.get("likesCount")).isEqualTo(5);
                    })
                    .verifyComplete();

            verify(commentService, never()).unlikeCommentAndReturnCount(anyLong());
        }

        @Test
        @DisplayName("AUD18-L2: like on nonexistent/foreign comment 404s BEFORE touching Redis")
        void toggleLike_UnknownComment_ShouldErrorWithoutRedisWrite() {
            when(commentService.getApprovedCommentForArticle(SLUG, COMMENT_ID))
                    .thenReturn(Mono.error(new dev.catananti.exception.ResourceNotFoundException(
                            "Comment", "id", COMMENT_ID)));

            StepVerifier.create(likeController.toggleCommentLike(SLUG, COMMENT_ID, mockRequest))
                    .expectError(dev.catananti.exception.ResourceNotFoundException.class)
                    .verify();

            verifyNoInteractions(deduplicationService);
            verify(commentService, never()).likeCommentAndReturnCount(anyLong());
        }

        @Test
        @DisplayName("AUD18-L2: like status on nonexistent/foreign comment 404s")
        void likeStatus_UnknownComment_ShouldError() {
            when(commentService.getApprovedCommentForArticle(SLUG, COMMENT_ID))
                    .thenReturn(Mono.error(new dev.catananti.exception.ResourceNotFoundException(
                            "Comment", "id", COMMENT_ID)));
            when(deduplicationService.hasLikedComment(eq(COMMENT_ID), any())).thenReturn(Mono.just(false));
            when(commentService.getCommentLikeCount(COMMENT_ID)).thenReturn(Mono.just(0));

            StepVerifier.create(likeController.getCommentLikeStatus(SLUG, COMMENT_ID, mockRequest))
                    .expectError(dev.catananti.exception.ResourceNotFoundException.class)
                    .verify();
        }
    }
}
