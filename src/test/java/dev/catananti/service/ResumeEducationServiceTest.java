package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeEducation;
import dev.catananti.repository.ResumeEducationRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResumeEducationServiceTest {

    @Mock
    private ResumeEducationRepository educationRepository;

    @Mock
    private IdService idService;

    @InjectMocks
    private ResumeEducationService educationService;

    private Long profileId;

    @BeforeEach
    void setUp() {
        profileId = 100L;
    }

    // ============================
    // saveEducations
    // ============================
    @Nested
    @DisplayName("saveEducations")
    class SaveEducations {

        @Test
        @DisplayName("should save education entries successfully")
        void success() {
            var entry = ResumeProfileRequest.EducationEntry.builder()
                    .institution("MIT")
                    .degree("BSc")
                    .fieldOfStudy("Computer Science")
                    .startDate("2018-09")
                    .endDate("2022-06")
                    .sortOrder(0)
                    .build();

            when(idService.nextId()).thenReturn(1001L);
            when(educationRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            StepVerifier.create(educationService.saveEducations(profileId, List.of(entry)))
                    .verifyComplete();

            verify(educationRepository).saveAll(anyIterable());
        }

        @Test
        @DisplayName("should complete immediately for null list")
        void nullList_completesEmpty() {
            StepVerifier.create(educationService.saveEducations(profileId, null))
                    .verifyComplete();

            verifyNoInteractions(educationRepository);
        }

        @Test
        @DisplayName("should complete immediately for empty list")
        void emptyList_completesEmpty() {
            StepVerifier.create(educationService.saveEducations(profileId, List.of()))
                    .verifyComplete();

            verifyNoInteractions(educationRepository);
        }

        @Test
        @DisplayName("should assign auto-incremented sortOrder when not provided")
        void autoSortOrder() {
            var entry1 = ResumeProfileRequest.EducationEntry.builder()
                    .institution("MIT")
                    .degree("BSc")
                    .sortOrder(null)
                    .build();
            var entry2 = ResumeProfileRequest.EducationEntry.builder()
                    .institution("Stanford")
                    .degree("MSc")
                    .sortOrder(null)
                    .build();

            when(idService.nextId()).thenReturn(1001L, 1002L);
            when(educationRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            StepVerifier.create(educationService.saveEducations(profileId, List.of(entry1, entry2)))
                    .verifyComplete();

            verify(educationRepository).saveAll(argThat((Iterable<ResumeEducation> entities) -> {
                var list = new java.util.ArrayList<ResumeEducation>();
                entities.forEach(list::add);
                return list.size() == 2
                        && list.get(0).getSortOrder() == 0
                        && list.get(1).getSortOrder() == 1;
            }));
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
            when(educationRepository.deleteByProfileId(profileId)).thenReturn(Mono.empty());

            StepVerifier.create(educationService.deleteByProfileId(profileId))
                    .verifyComplete();

            verify(educationRepository).deleteByProfileId(profileId);
        }
    }

    // ============================
    // findByProfileId
    // ============================
    @Nested
    @DisplayName("findByProfileId")
    class FindByProfileId {

        @Test
        @DisplayName("should return mapped education responses")
        void returnsResults() {
            var entity = ResumeEducation.builder()
                    .id(500L)
                    .profileId(profileId)
                    .institution("MIT")
                    .location("Cambridge, MA")
                    .degree("BSc")
                    .fieldOfStudy("Computer Science")
                    .startDate("2018-09")
                    .endDate("2022-06")
                    .description("Summa cum laude")
                    .sortOrder(0)
                    .build();

            when(educationRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.just(entity));

            StepVerifier.create(educationService.findByProfileId(profileId))
                    .assertNext(list -> {
                        assertThat(list).hasSize(1);
                        ResumeProfileResponse.EducationResponse resp = list.getFirst();
                        assertThat(resp.getId()).isEqualTo("500");
                        assertThat(resp.getInstitution()).isEqualTo("MIT");
                        assertThat(resp.getDegree()).isEqualTo("BSc");
                        assertThat(resp.getFieldOfStudy()).isEqualTo("Computer Science");
                        assertThat(resp.getStartDate()).isEqualTo("2018-09");
                        assertThat(resp.getEndDate()).isEqualTo("2022-06");
                        assertThat(resp.getDescription()).isEqualTo("Summa cum laude");
                        assertThat(resp.getSortOrder()).isEqualTo(0);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty list when no educations found")
        void emptyResults() {
            when(educationRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.empty());

            StepVerifier.create(educationService.findByProfileId(profileId))
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();
        }
    }
}
