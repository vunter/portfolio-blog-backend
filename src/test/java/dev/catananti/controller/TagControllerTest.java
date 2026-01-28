package dev.catananti.controller;

import dev.catananti.dto.TagResponse;
import dev.catananti.service.TagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TagControllerTest {

    @Mock
    private TagService tagService;

    @InjectMocks
    private TagController controller;

    private TagResponse javaTag;
    private TagResponse springTag;

    @BeforeEach
    void setUp() {
        javaTag = TagResponse.builder()
                .id("1")
                .name("Java")
                .slug("java")
                .description("Java programming language")
                .color("#FF5722")
                .articleCount(15)
                .createdAt(LocalDateTime.now().minusDays(30))
                .build();

        springTag = TagResponse.builder()
                .id("2")
                .name("Spring Boot")
                .slug("spring-boot")
                .description("Spring Boot framework")
                .color("#6DB33F")
                .articleCount(10)
                .createdAt(LocalDateTime.now().minusDays(20))
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/tags")
    class GetAllTags {

        @Test
        @DisplayName("Should return all tags with locale")
        void shouldReturnAllTagsWithLocale() {
            when(tagService.getAllTags("pt")).thenReturn(Flux.just(javaTag, springTag));

            StepVerifier.create(controller.getAllTags("pt"))
                    .assertNext(tags -> {
                        assertThat(tags).hasSize(2);
                        assertThat(tags.get(0).getName()).isEqualTo("Java");
                        assertThat(tags.get(1).getSlug()).isEqualTo("spring-boot");
                    })
                    .verifyComplete();

            verify(tagService).getAllTags("pt");
        }

        @Test
        @DisplayName("Should return all tags without locale")
        void shouldReturnAllTagsWithoutLocale() {
            when(tagService.getAllTags(null)).thenReturn(Flux.just(javaTag, springTag));

            StepVerifier.create(controller.getAllTags(null))
                    .assertNext(tags -> {
                        assertThat(tags).hasSize(2);
                        assertThat(tags.get(0).getArticleCount()).isEqualTo(15);
                    })
                    .verifyComplete();

            verify(tagService).getAllTags(null);
        }

        @Test
        @DisplayName("Should return empty list when no tags exist")
        void shouldReturnEmptyListWhenNoTags() {
            when(tagService.getAllTags(null)).thenReturn(Flux.empty());

            StepVerifier.create(controller.getAllTags(null))
                    .assertNext(tags -> assertThat(tags).isEmpty())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/tags/{slug}")
    class GetTagBySlug {

        @Test
        @DisplayName("Should return tag when found")
        void shouldReturnTagWhenFound() {
            when(tagService.getTagBySlug("java", null))
                    .thenReturn(reactor.core.publisher.Mono.just(javaTag));

            StepVerifier.create(controller.getTagBySlug("java", null))
                    .assertNext(tag -> {
                        assertThat(tag.getName()).isEqualTo("Java");
                        assertThat(tag.getSlug()).isEqualTo("java");
                        assertThat(tag.getArticleCount()).isEqualTo(15);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should complete empty when tag not found")
        void shouldCompleteEmptyWhenNotFound() {
            when(tagService.getTagBySlug("nonexistent", null))
                    .thenReturn(reactor.core.publisher.Mono.empty());

            StepVerifier.create(controller.getTagBySlug("nonexistent", null))
                    .verifyComplete();
        }
    }
}
