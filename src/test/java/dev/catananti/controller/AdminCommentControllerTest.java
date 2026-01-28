package dev.catananti.controller;

import dev.catananti.dto.CommentResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.CommentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminCommentControllerTest {

    @Mock
    private CommentService commentService;

    @InjectMocks
    private AdminCommentController controller;

    private CommentResponse pendingComment;
    private CommentResponse approvedComment;

    @BeforeEach
    void setUp() {
        pendingComment = CommentResponse.builder()
                .id("5001")
                .articleId("1001")
                .authorName("João Silva")
                .content("Ótimo artigo sobre Java!")
                .status("PENDING")
                .createdAt(LocalDateTime.now().minusHours(2))
                .build();

        approvedComment = CommentResponse.builder()
                .id("5002")
                .articleId("1001")
                .authorName("Maria Santos")
                .content("Concordo, Spring Boot é incrível.")
                .status("APPROVED")
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/comments")
    class ListComments {

        @Test
        @DisplayName("Should return pending comments with pagination")
        void shouldReturnPendingComments() {
            PageResponse<CommentResponse> page = PageResponse.<CommentResponse>builder()
                    .content(List.of(pendingComment))
                    .page(0)
                    .size(20)
                    .totalElements(1)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();

            when(commentService.getAdminCommentsByStatus("PENDING", 0, 20))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getCommentsByStatus("PENDING", 0, 20))
                    .assertNext(result -> {
                        assertThat(result.getContent()).hasSize(1);
                        assertThat(result.getContent().getFirst().getStatus()).isEqualTo("PENDING");
                        assertThat(result.getContent().getFirst().getAuthorName()).isEqualTo("João Silva");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/comments/article/{articleId}")
    class GetByArticle {

        @Test
        @DisplayName("Should return all comments for article")
        void shouldReturnArticleComments() {
            when(commentService.getAdminCommentsByArticleId(1001L))
                    .thenReturn(Flux.just(pendingComment, approvedComment));

            StepVerifier.create(controller.getCommentsByArticle(1001L))
                    .assertNext(comments -> {
                        assertThat(comments).hasSize(2);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Comment Moderation Actions")
    class ModerationActions {

        @Test
        @DisplayName("Should approve pending comment")
        void shouldApproveComment() {
            CommentResponse approved = CommentResponse.builder()
                    .id("5001")
                    .status("APPROVED")
                    .authorName("João Silva")
                    .content("Ótimo artigo sobre Java!")
                    .build();

            when(commentService.adminApproveComment(5001L)).thenReturn(Mono.just(approved));

            StepVerifier.create(controller.approveComment(5001L))
                    .assertNext(comment -> {
                        assertThat(comment.getStatus()).isEqualTo("APPROVED");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject comment")
        void shouldRejectComment() {
            CommentResponse rejected = CommentResponse.builder()
                    .id("5001")
                    .status("REJECTED")
                    .build();

            when(commentService.adminRejectComment(5001L)).thenReturn(Mono.just(rejected));

            StepVerifier.create(controller.rejectComment(5001L))
                    .assertNext(comment -> assertThat(comment.getStatus()).isEqualTo("REJECTED"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should mark comment as spam")
        void shouldMarkAsSpam() {
            CommentResponse spammed = CommentResponse.builder()
                    .id("5001")
                    .status("SPAM")
                    .build();

            when(commentService.adminMarkAsSpam(5001L)).thenReturn(Mono.just(spammed));

            StepVerifier.create(controller.markAsSpam(5001L))
                    .assertNext(comment -> assertThat(comment.getStatus()).isEqualTo("SPAM"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should delete comment")
        void shouldDeleteComment() {
            when(commentService.adminDeleteComment(5001L)).thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteComment(5001L))
                    .verifyComplete();

            verify(commentService).adminDeleteComment(5001L);
        }
    }
}
