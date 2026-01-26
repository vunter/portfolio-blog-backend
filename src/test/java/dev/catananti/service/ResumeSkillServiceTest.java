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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResumeSkillServiceTest {

    @Mock
    private ResumeSkillRepository skillRepository;

    @Mock
    private IdService idService;

    @InjectMocks
    private ResumeSkillService skillService;

    private Long profileId;

    @BeforeEach
    void setUp() {
        profileId = 300L;
    }

    // ============================
    // saveSkills
    // ============================
    @Nested
    @DisplayName("saveSkills")
    class SaveSkills {

        @Test
        @DisplayName("should save skill entries successfully")
        void success() {
            var entry = ResumeProfileRequest.SkillEntry.builder()
                    .category("Languages & Runtimes")
                    .content("Java, Kotlin, TypeScript")
                    .sortOrder(0)
                    .build();

            when(idService.nextId()).thenReturn(3001L);
            when(skillRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            StepVerifier.create(skillService.saveSkills(profileId, List.of(entry)))
                    .verifyComplete();

            verify(skillRepository).saveAll(anyIterable());
        }

        @Test
        @DisplayName("should complete immediately for null list")
        void nullList_completesEmpty() {
            StepVerifier.create(skillService.saveSkills(profileId, null))
                    .verifyComplete();

            verifyNoInteractions(skillRepository);
        }

        @Test
        @DisplayName("should complete immediately for empty list")
        void emptyList_completesEmpty() {
            StepVerifier.create(skillService.saveSkills(profileId, List.of()))
                    .verifyComplete();

            verifyNoInteractions(skillRepository);
        }
    }

    // ============================
    // deleteByProfileId
    // ============================
    @Nested
    @DisplayName("deleteByProfileId")
    class DeleteByProfileId {

        @Test
        @DisplayName("should delegate to repository")
        void delegatesToRepository() {
            when(skillRepository.deleteByProfileId(profileId)).thenReturn(Mono.empty());

            StepVerifier.create(skillService.deleteByProfileId(profileId))
                    .verifyComplete();

            verify(skillRepository).deleteByProfileId(profileId);
        }
    }

    // ============================
    // findByProfileId
    // ============================
    @Nested
    @DisplayName("findByProfileId")
    class FindByProfileId {

        @Test
        @DisplayName("should return mapped skill responses")
        void returnsResults() {
            var entity = ResumeSkill.builder()
                    .id(700L)
                    .profileId(profileId)
                    .category("Backend & Frameworks")
                    .content("Spring Boot, Micronaut, Quarkus")
                    .sortOrder(1)
                    .build();

            when(skillRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.just(entity));

            StepVerifier.create(skillService.findByProfileId(profileId))
                    .assertNext(list -> {
                        assertThat(list).hasSize(1);
                        ResumeProfileResponse.SkillResponse resp = list.getFirst();
                        assertThat(resp.getId()).isEqualTo("700");
                        assertThat(resp.getCategory()).isEqualTo("Backend & Frameworks");
                        assertThat(resp.getContent()).isEqualTo("Spring Boot, Micronaut, Quarkus");
                        assertThat(resp.getSortOrder()).isEqualTo(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty list when no skills found")
        void emptyResults() {
            when(skillRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.empty());

            StepVerifier.create(skillService.findByProfileId(profileId))
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();
        }
    }
}
