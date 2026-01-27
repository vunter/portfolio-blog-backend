package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeLanguage;
import dev.catananti.repository.ResumeLanguageRepository;
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
class ResumeLanguageServiceTest {

    @Mock
    private ResumeLanguageRepository languageRepository;

    @Mock
    private IdService idService;

    @InjectMocks
    private ResumeLanguageService languageService;

    private Long profileId;

    @BeforeEach
    void setUp() {
        profileId = 400L;
    }

    // ============================
    // saveLanguages
    // ============================
    @Nested
    @DisplayName("saveLanguages")
    class SaveLanguages {

        @Test
        @DisplayName("should save language entries successfully")
        void success() {
            var entry = ResumeProfileRequest.LanguageEntry.builder()
                    .name("English")
                    .proficiency("Native")
                    .sortOrder(0)
                    .build();

            when(idService.nextId()).thenReturn(4001L);
            when(languageRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            StepVerifier.create(languageService.saveLanguages(profileId, List.of(entry)))
                    .verifyComplete();

            verify(languageRepository).saveAll(anyIterable());
        }

        @Test
        @DisplayName("should complete immediately for null list")
        void nullList_completesEmpty() {
            StepVerifier.create(languageService.saveLanguages(profileId, null))
                    .verifyComplete();

            verifyNoInteractions(languageRepository);
        }

        @Test
        @DisplayName("should complete immediately for empty list")
        void emptyList_completesEmpty() {
            StepVerifier.create(languageService.saveLanguages(profileId, List.of()))
                    .verifyComplete();

            verifyNoInteractions(languageRepository);
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
            when(languageRepository.deleteByProfileId(profileId)).thenReturn(Mono.empty());

            StepVerifier.create(languageService.deleteByProfileId(profileId))
                    .verifyComplete();

            verify(languageRepository).deleteByProfileId(profileId);
        }
    }

    // ============================
    // findByProfileId
    // ============================
    @Nested
    @DisplayName("findByProfileId")
    class FindByProfileId {

        @Test
        @DisplayName("should return mapped language responses")
        void returnsResults() {
            var entity = ResumeLanguage.builder()
                    .id(800L)
                    .profileId(profileId)
                    .name("Portuguese")
                    .proficiency("Native")
                    .sortOrder(0)
                    .build();

            when(languageRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.just(entity));

            StepVerifier.create(languageService.findByProfileId(profileId))
                    .assertNext(list -> {
                        assertThat(list).hasSize(1);
                        ResumeProfileResponse.LanguageResponse resp = list.getFirst();
                        assertThat(resp.getId()).isEqualTo("800");
                        assertThat(resp.getName()).isEqualTo("Portuguese");
                        assertThat(resp.getProficiency()).isEqualTo("Native");
                        assertThat(resp.getSortOrder()).isEqualTo(0);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty list when no languages found")
        void emptyResults() {
            when(languageRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.empty());

            StepVerifier.create(languageService.findByProfileId(profileId))
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();
        }
    }
}
