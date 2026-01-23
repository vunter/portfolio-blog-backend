package dev.catananti.service;

import dev.catananti.dto.TagRequest;
import dev.catananti.dto.TagResponse;
import dev.catananti.entity.LocalizedText;
import dev.catananti.entity.Tag;
import dev.catananti.exception.DuplicateResourceException;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.TagRepository;
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
class TagServiceTest {

    @Mock private TagRepository tagRepository;
    @Mock private IdService idService;

    @InjectMocks
    private TagService tagService;

    private Tag javaTag;
    private Tag springTag;

    @BeforeEach
    void setUp() {
        javaTag = Tag.builder()
                .id(101L)
                .name(LocalizedText.ofEnglish("Java"))
                .slug("java")
                .description(LocalizedText.ofEnglish("Java programming"))
                .color("#E76F00")
                .createdAt(LocalDateTime.now().minusDays(60))
                .build();

        springTag = Tag.builder()
                .id(102L)
                .name(LocalizedText.ofEnglish("Spring Boot"))
                .slug("spring-boot")
                .description(LocalizedText.ofEnglish("Spring Boot framework"))
                .color("#6DB33F")
                .createdAt(LocalDateTime.now().minusDays(30))
                .build();
    }

    @Nested
    @DisplayName("getAllTags")
    class GetAllTags {

        @Test
        @DisplayName("Should return all tags with article counts")
        void shouldReturnAllTags() {
            when(tagRepository.findAll()).thenReturn(Flux.just(javaTag, springTag));
            when(tagRepository.countPublishedArticlesByTagId(101L)).thenReturn(Mono.just(15L));
            when(tagRepository.countPublishedArticlesByTagId(102L)).thenReturn(Mono.just(8L));

            StepVerifier.create(tagService.getAllTags("en").collectList())
                    .assertNext(tags -> {
                        assertThat(tags).hasSize(2);
                        assertThat(tags.get(0).getName()).isEqualTo("Java");
                        assertThat(tags.get(0).getArticleCount()).isEqualTo(15);
                        assertThat(tags.get(1).getName()).isEqualTo("Spring Boot");
                        assertThat(tags.get(1).getArticleCount()).isEqualTo(8);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty flux when no tags exist")
        void shouldReturnEmpty() {
            when(tagRepository.findAll()).thenReturn(Flux.empty());

            StepVerifier.create(tagService.getAllTags("en").collectList())
                    .assertNext(tags -> assertThat(tags).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should resolve names for requested locale")
        void shouldResolveForLocale() {
            LocalizedText multiName = new LocalizedText(java.util.Map.of("en", "Tutorial", "pt-br", "Tutorial", "es", "Tutoriales"));
            Tag tutorialTag = Tag.builder()
                    .id(103L)
                    .name(multiName)
                    .slug("tutorial")
                    .createdAt(LocalDateTime.now())
                    .build();

            when(tagRepository.findAll()).thenReturn(Flux.just(tutorialTag));
            when(tagRepository.countPublishedArticlesByTagId(103L)).thenReturn(Mono.just(5L));

            StepVerifier.create(tagService.getAllTags("es").collectList())
                    .assertNext(tags -> {
                        assertThat(tags).hasSize(1);
                        assertThat(tags.get(0).getName()).isEqualTo("Tutoriales");
                        assertThat(tags.get(0).getNames()).containsKeys("en", "pt-br", "es");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getTagBySlug")
    class GetTagBySlug {

        @Test
        @DisplayName("Should return tag by slug")
        void shouldReturnTag() {
            when(tagRepository.findBySlug("java")).thenReturn(Mono.just(javaTag));
            when(tagRepository.countPublishedArticlesByTagId(101L)).thenReturn(Mono.just(15L));

            StepVerifier.create(tagService.getTagBySlug("java", "en"))
                    .assertNext(tag -> {
                        assertThat(tag.getSlug()).isEqualTo("java");
                        assertThat(tag.getName()).isEqualTo("Java");
                        assertThat(tag.getColor()).isEqualTo("#E76F00");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for unknown slug")
        void shouldThrowForUnknownSlug() {
            when(tagRepository.findBySlug("nonexistent")).thenReturn(Mono.empty());

            StepVerifier.create(tagService.getTagBySlug("nonexistent", "en"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("getTagById")
    class GetTagById {

        @Test
        @DisplayName("Should return tag by ID")
        void shouldReturnTag() {
            when(tagRepository.findById(101L)).thenReturn(Mono.just(javaTag));
            when(tagRepository.countPublishedArticlesByTagId(101L)).thenReturn(Mono.just(15L));

            StepVerifier.create(tagService.getTagById(101L))
                    .assertNext(tag -> {
                        assertThat(tag.getId()).isEqualTo("101");
                        assertThat(tag.getName()).isEqualTo("Java");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for unknown ID")
        void shouldThrowForUnknownId() {
            when(tagRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(tagService.getTagById(999L))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("createTag")
    class CreateTag {

        @Test
        @DisplayName("Should create tag with unique slug")
        void shouldCreateTag() {
            TagRequest request = TagRequest.builder()
                    .name("Docker")
                    .slug("docker")
                    .description("Containerization")
                    .color("#2496ED")
                    .build();

            when(tagRepository.existsBySlug("docker")).thenReturn(Mono.just(false));
            when(idService.nextId()).thenReturn(103L);
            when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(tagRepository.countPublishedArticlesByTagId(103L)).thenReturn(Mono.just(0L));

            StepVerifier.create(tagService.createTag(request))
                    .assertNext(tag -> {
                        assertThat(tag.getName()).isEqualTo("Docker");
                        assertThat(tag.getSlug()).isEqualTo("docker");
                        assertThat(tag.getColor()).isEqualTo("#2496ED");
                        assertThat(tag.getArticleCount()).isZero();
                        assertThat(tag.getNames()).containsEntry("en", "Docker");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw DuplicateResourceException for existing slug")
        void shouldThrowForDuplicateSlug() {
            TagRequest request = TagRequest.builder()
                    .name("Java")
                    .slug("java")
                    .build();

            when(tagRepository.existsBySlug("java")).thenReturn(Mono.just(true));

            StepVerifier.create(tagService.createTag(request))
                    .expectError(DuplicateResourceException.class)
                    .verify();

            verify(tagRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateTag")
    class UpdateTag {

        @Test
        @DisplayName("Should update existing tag preserving translations")
        void shouldUpdateTag() {
            TagRequest request = TagRequest.builder()
                    .name("Java 21+")
                    .slug("java")
                    .description("Java 21 LTS and beyond")
                    .color("#E76F00")
                    .build();

            when(tagRepository.findById(101L)).thenReturn(Mono.just(javaTag));
            when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(tagRepository.countPublishedArticlesByTagId(101L)).thenReturn(Mono.just(15L));

            StepVerifier.create(tagService.updateTag(101L, request))
                    .assertNext(tag -> {
                        assertThat(tag.getName()).isEqualTo("Java 21+");
                        assertThat(tag.getDescription()).isEqualTo("Java 21 LTS and beyond");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException for unknown tag")
        void shouldThrowForUnknownTag() {
            TagRequest request = TagRequest.builder().name("X").slug("x").build();
            when(tagRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(tagService.updateTag(999L, request))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("deleteTag")
    class DeleteTag {

        @Test
        @DisplayName("Should delete existing tag")
        void shouldDeleteTag() {
            when(tagRepository.findById(101L)).thenReturn(Mono.just(javaTag));
            when(tagRepository.deleteById(101L)).thenReturn(Mono.empty());

            StepVerifier.create(tagService.deleteTag(101L))
                    .verifyComplete();

            verify(tagRepository).deleteById(101L);
        }

        @Test
        @DisplayName("Should complete silently when tag not found (idempotent)")
        void shouldBeIdempotent() {
            when(tagRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(tagService.deleteTag(999L))
                    .verifyComplete();
        }
    }
}
