package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeSkill;
import dev.catananti.repository.ResumeSkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeSkillService")
class ResumeSkillServiceTest {

    @Mock private ResumeSkillRepository skillRepository;
    @Mock private IdService idService;

    private ResumeSkillService service;

    @BeforeEach
    void setUp() {
        service = new ResumeSkillService(skillRepository, idService);
    }

    @Nested
    @DisplayName("saveSkills")
    class SaveSkills {

        @Test
        @DisplayName("should save skill entries")
        void shouldSaveSkills() {
            when(idService.nextId()).thenReturn(100L, 101L);
            when(skillRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            var skills = List.of(
                    ResumeProfileRequest.SkillEntry.builder().category("Backend").content("Java, Spring").build(),
                    ResumeProfileRequest.SkillEntry.builder().category("Frontend").content("React").sortOrder(5).build()
            );

            StepVerifier.create(service.saveSkills(1L, skills))
                    .verifyComplete();

            verify(skillRepository).saveAll(anyIterable());
        }

        @Test
        @DisplayName("should return empty for null skills")
        void shouldReturnEmptyForNull() {
            StepVerifier.create(service.saveSkills(1L, null))
                    .verifyComplete();

            verifyNoInteractions(skillRepository);
        }

        @Test
        @DisplayName("should return empty for empty list")
        void shouldReturnEmptyForEmptyList() {
            StepVerifier.create(service.saveSkills(1L, List.of()))
                    .verifyComplete();

            verifyNoInteractions(skillRepository);
        }

        @Test
        @DisplayName("should use index as default sort order")
        @SuppressWarnings("unchecked")
        void shouldUseIndexAsDefaultSortOrder() {
            when(idService.nextId()).thenReturn(100L, 101L);
            when(skillRepository.saveAll(anyIterable())).thenAnswer(inv -> {
                Iterable<ResumeSkill> saved = inv.getArgument(0);
                return Flux.fromIterable(saved);
            });

            var skills = List.of(
                    ResumeProfileRequest.SkillEntry.builder().category("A").content("a").build(),
                    ResumeProfileRequest.SkillEntry.builder().category("B").content("b").build()
            );

            StepVerifier.create(service.saveSkills(1L, skills))
                    .verifyComplete();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Iterable<ResumeSkill>> captor = ArgumentCaptor.forClass(Iterable.class);
            verify(skillRepository).saveAll(captor.capture());

            var saved = new java.util.ArrayList<ResumeSkill>();
            captor.getValue().forEach(saved::add);
            assertThat(saved).hasSize(2);
            assertThat(saved.get(0).getSortOrder()).isZero();
            assertThat(saved.get(1).getSortOrder()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("deleteByProfileId")
    class DeleteByProfileId {

        @Test
        @DisplayName("should delete all skills for profile")
        void shouldDelete() {
            when(skillRepository.deleteByProfileId(1L)).thenReturn(Mono.empty());

            StepVerifier.create(service.deleteByProfileId(1L))
                    .verifyComplete();

            verify(skillRepository).deleteByProfileId(1L);
        }
    }

    @Nested
    @DisplayName("mergeSkills")
    class MergeSkills {

        @Test
        @DisplayName("should return empty for null input")
        void shouldReturnEmptyForNull() {
            StepVerifier.create(service.mergeSkills(1L, null))
                    .verifyComplete();

            verifyNoInteractions(skillRepository);
        }

        @Test
        @DisplayName("should delete all when empty list")
        void shouldDeleteAllForEmptyList() {
            when(skillRepository.deleteByProfileId(1L)).thenReturn(Mono.empty());

            StepVerifier.create(service.mergeSkills(1L, List.of()))
                    .verifyComplete();

            verify(skillRepository).deleteByProfileId(1L);
        }

        @Test
        @DisplayName("should insert new entries")
        void shouldInsertNewEntries() {
            when(skillRepository.findByProfileIdOrderBySortOrderAsc(1L)).thenReturn(Flux.empty());
            when(idService.nextId()).thenReturn(200L);
            when(skillRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            var incoming = List.of(
                    ResumeProfileRequest.SkillEntry.builder().category("New").content("new skill").build()
            );

            StepVerifier.create(service.mergeSkills(1L, incoming))
                    .verifyComplete();

            verify(skillRepository).saveAll(anyIterable());
        }

        @Test
        @DisplayName("should update existing and delete removed entries")
        void shouldUpdateExistingAndDeleteRemoved() {
            var existing1 = ResumeSkill.builder().id(10L).profileId(1L).category("Old1").content("old1").sortOrder(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            var existing2 = ResumeSkill.builder().id(20L).profileId(1L).category("Old2").content("old2").sortOrder(1)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

            when(skillRepository.findByProfileIdOrderBySortOrderAsc(1L)).thenReturn(Flux.just(existing1, existing2));
            when(skillRepository.deleteAllById(anyIterable())).thenReturn(Mono.empty());
            when(skillRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            var incoming = List.of(
                    ResumeProfileRequest.SkillEntry.builder().id("10").category("Updated").content("updated skill").build()
            );

            StepVerifier.create(service.mergeSkills(1L, incoming))
                    .verifyComplete();

            verify(skillRepository).deleteAllById(argThat(ids -> {
                var list = new java.util.ArrayList<Long>();
                ids.forEach(list::add);
                return list.contains(20L) && !list.contains(10L);
            }));
        }
    }

    @Nested
    @DisplayName("findByProfileId")
    class FindByProfileId {

        @Test
        @DisplayName("should map entities to response DTOs")
        void shouldMapToResponse() {
            var skill = ResumeSkill.builder().id(10L).profileId(1L).category("Backend").content("Java").sortOrder(0)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            when(skillRepository.findByProfileIdOrderBySortOrderAsc(1L)).thenReturn(Flux.just(skill));

            StepVerifier.create(service.findByProfileId(1L))
                    .assertNext(list -> {
                        assertThat(list).hasSize(1);
                        ResumeProfileResponse.SkillResponse resp = list.get(0);
                        assertThat(resp.getId()).isEqualTo("10");
                        assertThat(resp.getCategory()).isEqualTo("Backend");
                        assertThat(resp.getContent()).isEqualTo("Java");
                        assertThat(resp.getSortOrder()).isZero();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty list when no skills")
        void shouldReturnEmptyList() {
            when(skillRepository.findByProfileIdOrderBySortOrderAsc(1L)).thenReturn(Flux.empty());

            StepVerifier.create(service.findByProfileId(1L))
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();
        }
    }
}
