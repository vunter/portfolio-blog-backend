package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeExperience;
import dev.catananti.repository.ResumeExperienceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResumeExperienceServiceTest {

    @Mock
    private ResumeExperienceRepository experienceRepository;

    @Mock
    private IdService idService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ResumeExperienceService experienceService;

    private Long profileId;

    @BeforeEach
    void setUp() {
        profileId = 200L;
    }

    // ============================
    // saveExperiences
    // ============================
    @Nested
    @DisplayName("saveExperiences")
    class SaveExperiences {

        @Test
        @DisplayName("should save experience entries successfully")
        void success() {
            var entry = ResumeProfileRequest.ExperienceEntry.builder()
                    .company("Acme Corp")
                    .position("Senior Developer")
                    .startDate("2020-01")
                    .endDate("2024-12")
                    .bullets(List.of("Led team of 5", "Implemented CI/CD"))
                    .sortOrder(0)
                    .build();

            when(idService.nextId()).thenReturn(2001L);
            when(experienceRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            StepVerifier.create(experienceService.saveExperiences(profileId, List.of(entry)))
                    .verifyComplete();

            verify(experienceRepository).saveAll(anyIterable());
        }

        @Test
        @DisplayName("should complete immediately for null list")
        void nullList_completesEmpty() {
            StepVerifier.create(experienceService.saveExperiences(profileId, null))
                    .verifyComplete();

            verifyNoInteractions(experienceRepository);
        }

        @Test
        @DisplayName("should complete immediately for empty list")
        void emptyList_completesEmpty() {
            StepVerifier.create(experienceService.saveExperiences(profileId, List.of()))
                    .verifyComplete();

            verifyNoInteractions(experienceRepository);
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
            when(experienceRepository.deleteByProfileId(profileId)).thenReturn(Mono.empty());

            StepVerifier.create(experienceService.deleteByProfileId(profileId))
                    .verifyComplete();

            verify(experienceRepository).deleteByProfileId(profileId);
        }
    }

    // ============================
    // findByProfileId
    // ============================
    @Nested
    @DisplayName("findByProfileId")
    class FindByProfileId {

        @Test
        @DisplayName("should return mapped experience responses with bullets")
        void returnsResults() {
            var entity = ResumeExperience.builder()
                    .id(600L)
                    .profileId(profileId)
                    .company("Acme Corp")
                    .position("Senior Developer")
                    .startDate("2020-01")
                    .endDate("2024-12")
                    .bullets("[\"Led team of 5\",\"Built microservices\"]")
                    .sortOrder(0)
                    .build();

            when(experienceRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.just(entity));

            StepVerifier.create(experienceService.findByProfileId(profileId))
                    .assertNext(list -> {
                        assertThat(list).hasSize(1);
                        ResumeProfileResponse.ExperienceResponse resp = list.getFirst();
                        assertThat(resp.getId()).isEqualTo("600");
                        assertThat(resp.getCompany()).isEqualTo("Acme Corp");
                        assertThat(resp.getPosition()).isEqualTo("Senior Developer");
                        assertThat(resp.getBullets()).containsExactly("Led team of 5", "Built microservices");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty list when no experiences found")
        void emptyResults() {
            when(experienceRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.empty());

            StepVerifier.create(experienceService.findByProfileId(profileId))
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();
        }
    }

    // ============================
    // toJsonArray
    // ============================
    @Nested
    @DisplayName("toJsonArray")
    class ToJsonArray {

        @Test
        @DisplayName("should return '[]' for null input")
        void nullInput_returnsEmptyArray() {
            assertThat(experienceService.toJsonArray(null)).isEqualTo("[]");
        }

        @Test
        @DisplayName("should return '[]' for empty list")
        void emptyList_returnsEmptyArray() {
            assertThat(experienceService.toJsonArray(List.of())).isEqualTo("[]");
        }

        @Test
        @DisplayName("should produce valid JSON array for items")
        void withItems_producesJsonArray() {
            String result = experienceService.toJsonArray(List.of("bullet one", "bullet two"));
            assertThat(result).isEqualTo("[\"bullet one\",\"bullet two\"]");
        }

        @Test
        @DisplayName("should escape quotes in items")
        void escapesQuotes() {
            String result = experienceService.toJsonArray(List.of("He said \"hello\""));
            assertThat(result).contains("\\\"hello\\\"");
        }

        @Test
        @DisplayName("should escape backslashes in items")
        void escapesBackslashes() {
            String result = experienceService.toJsonArray(List.of("path\\to\\file"));
            assertThat(result).contains("path\\\\to\\\\file");
        }
    }

    // ============================
    // fromJsonArray
    // ============================
    @Nested
    @DisplayName("fromJsonArray")
    class FromJsonArray {

        @Test
        @DisplayName("should return empty list for null input")
        void nullInput_returnsEmptyList() {
            assertThat(experienceService.fromJsonArray(null)).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for blank input")
        void blankInput_returnsEmptyList() {
            assertThat(experienceService.fromJsonArray("   ")).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for '[]'")
        void emptyArray_returnsEmptyList() {
            assertThat(experienceService.fromJsonArray("[]")).isEmpty();
        }

        @Test
        @DisplayName("should parse valid JSON array")
        void validJson_returnsList() {
            List<String> result = experienceService.fromJsonArray("[\"item1\",\"item2\",\"item3\"]");
            assertThat(result).containsExactly("item1", "item2", "item3");
        }

        @Test
        @DisplayName("should handle escaped characters in JSON")
        void escapedChars_handledCorrectly() {
            List<String> result = experienceService.fromJsonArray("[\"line with \\\"quotes\\\"\"]");
            assertThat(result).hasSize(1);
            assertThat(result.getFirst()).contains("quotes");
        }
    }
}
