package dev.catananti.controller;

import dev.catananti.dto.CommentRequest;
import dev.catananti.dto.CommentResponse;
import dev.catananti.dto.PageResponse;
import dev.catananti.service.CommentService;
import dev.catananti.service.RecaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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

            when(commentService.getApprovedCommentsByArticleSlugPaginated("spring-boot-guide", 0, 20))
                    .thenReturn(Mono.just(page));

            StepVerifier.create(controller.getComments("spring-boot-guide", 0, 20))
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

            when(recaptchaService.verify("valid-token", "comment"))
                    .thenReturn(Mono.empty());
            when(commentService.createComment("spring-boot-guide", commentRequest))
                    .thenReturn(Mono.just(created));

            StepVerifier.create(controller.createComment("spring-boot-guide", commentRequest))
                    .assertNext(result -> {
                        assertThat(result.getId()).isEqualTo("102");
                        assertThat(result.getStatus()).isEqualTo("PENDING");
                        assertThat(result.getAuthorName()).isEqualTo("John Doe");
                    })
                    .verifyComplete();

            verify(recaptchaService).verify("valid-token", "comment");
            verify(commentService).createComment("spring-boot-guide", commentRequest);
        }
    }
}
