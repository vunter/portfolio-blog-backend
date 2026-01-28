package dev.catananti.controller;

import dev.catananti.dto.TagRequest;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTagControllerTest {

    @Mock
    private TagService tagService;

    @InjectMocks
    private AdminTagController controller;

    private TagResponse javaTag;
    private TagResponse springTag;
    private TagResponse angularTag;

    @BeforeEach
    void setUp() {
        javaTag = TagResponse.builder()
                .id("101")
                .name("Java")
                .slug("java")
                .description("Java programming language")
                .color("#E76F00")
                .articleCount(15)
                .createdAt(LocalDateTime.now().minusDays(60))
                .build();

        springTag = TagResponse.builder()
                .id("102")
                .name("Spring Boot")
                .slug("spring-boot")
                .description("Spring Boot framework")
                .color("#6DB33F")
                .articleCount(12)
                .createdAt(LocalDateTime.now().minusDays(45))
                .build();

        angularTag = TagResponse.builder()
                .id("103")
                .name("Angular")
                .slug("angular")
                .description("Angular web framework")
                .color("#DD0031")
                .articleCount(8)
                .createdAt(LocalDateTime.now().minusDays(30))
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/tags")
    class ListTags {

        @Test
        @DisplayName("Should return all tags with article counts")
        void shouldReturnAllTags() {
            when(tagService.getAllTags("en")).thenReturn(Flux.just(javaTag, springTag, angularTag));

            StepVerifier.create(controller.getAllTags("en"))
                    .assertNext(tags -> {
                        assertThat(tags).hasSize(3);
                        assertThat(tags.get(0).getSlug()).isEqualTo("java");
                        assertThat(tags.get(0).getArticleCount()).isEqualTo(15);
                        assertThat(tags.get(1).getSlug()).isEqualTo("spring-boot");
                        assertThat(tags.get(2).getSlug()).isEqualTo("angular");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty list when no tags")
        void shouldReturnEmptyList() {
            when(tagService.getAllTags("en")).thenReturn(Flux.empty());

            StepVerifier.create(controller.getAllTags("en"))
                    .assertNext(tags -> assertThat(tags).isEmpty())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/tags/{id}")
    class GetTagById {

        @Test
        @DisplayName("Should return tag by ID")
        void shouldReturnTag() {
            when(tagService.getTagById(101L)).thenReturn(Mono.just(javaTag));

            StepVerifier.create(controller.getTagById(101L))
                    .assertNext(tag -> {
                        assertThat(tag.getName()).isEqualTo("Java");
                        assertThat(tag.getColor()).isEqualTo("#E76F00");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/tags")
    class CreateTag {

        @Test
        @DisplayName("Should create new tag with valid data")
        void shouldCreateTag() {
            TagRequest request = TagRequest.builder()
                    .name("Docker")
                    .slug("docker")
                    .description("Container platform")
                    .color("#2496ED")
                    .build();

            TagResponse created = TagResponse.builder()
                    .id("104")
                    .name("Docker")
                    .slug("docker")
                    .description("Container platform")
                    .color("#2496ED")
                    .articleCount(0)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(tagService.createTag(request)).thenReturn(Mono.just(created));

            StepVerifier.create(controller.createTag(request))
                    .assertNext(tag -> {
                        assertThat(tag.getName()).isEqualTo("Docker");
                        assertThat(tag.getSlug()).isEqualTo("docker");
                        assertThat(tag.getColor()).isEqualTo("#2496ED");
                        assertThat(tag.getArticleCount()).isZero();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/tags/{id}")
    class UpdateTag {

        @Test
        @DisplayName("Should update existing tag")
        void shouldUpdateTag() {
            TagRequest request = TagRequest.builder()
                    .name("Java 21+")
                    .slug("java")
                    .description("Java LTS versions")
                    .color("#E76F00")
                    .build();

            TagResponse updated = TagResponse.builder()
                    .id("101")
                    .name("Java 21+")
                    .slug("java")
                    .description("Java LTS versions")
                    .color("#E76F00")
                    .articleCount(15)
                    .build();

            when(tagService.updateTag(101L, request)).thenReturn(Mono.just(updated));

            StepVerifier.create(controller.updateTag(101L, request))
                    .assertNext(tag -> {
                        assertThat(tag.getName()).isEqualTo("Java 21+");
                        assertThat(tag.getDescription()).isEqualTo("Java LTS versions");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/tags/{id}")
    class DeleteTag {

        @Test
        @DisplayName("Should delete tag")
        void shouldDeleteTag() {
            when(tagService.deleteTag(103L)).thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteTag(103L))
                    .verifyComplete();

            verify(tagService).deleteTag(103L);
        }
    }
}
