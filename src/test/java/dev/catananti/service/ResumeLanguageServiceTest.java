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

import java.time.LocalDateTime;
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

    @Nested
    @DisplayName("mergeLanguages")
    class MergeLanguages {

        @Test
        @DisplayName("should return empty for null input (preserve existing)")
        void shouldReturnEmptyForNull() {
            StepVerifier.create(languageService.mergeLanguages(profileId, null))
                    .verifyComplete();
            verifyNoInteractions(languageRepository);
        }

        @Test
        @DisplayName("should delete all for empty list")
        void shouldDeleteAllForEmpty() {
            when(languageRepository.deleteByProfileId(profileId)).thenReturn(Mono.empty());
            StepVerifier.create(languageService.mergeLanguages(profileId, List.of()))
                    .verifyComplete();
            verify(languageRepository).deleteByProfileId(profileId);
        }

        @Test
        @DisplayName("should insert new entries when no existing")
        void shouldInsertNew() {
            when(languageRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(idService.nextId()).thenReturn(500L);
            when(languageRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            var incoming = List.of(
                    ResumeProfileRequest.LanguageEntry.builder().name("English").proficiency("Native").build()
            );
            StepVerifier.create(languageService.mergeLanguages(profileId, incoming))
                    .verifyComplete();
            verify(languageRepository).saveAll(anyIterable());
        }

        @Test
        @DisplayName("should update existing and delete removed")
        void shouldUpdateAndDelete() {
            var existing = ResumeLanguage.builder().id(10L).profileId(profileId).name("Old").proficiency("B2")
                    .sortOrder(0).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            var toRemove = ResumeLanguage.builder().id(20L).profileId(profileId).name("Remove").proficiency("A1")
                    .sortOrder(1).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();

            when(languageRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.just(existing, toRemove));
            when(languageRepository.deleteAllById(anyIterable())).thenReturn(Mono.empty());
            when(languageRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            var incoming = List.of(
                    ResumeProfileRequest.LanguageEntry.builder().id("10").name("Updated").proficiency("C1").build()
            );
            StepVerifier.create(languageService.mergeLanguages(profileId, incoming))
                    .verifyComplete();
            verify(languageRepository).deleteAllById(argThat(ids -> {
                var list = new java.util.ArrayList<Long>();
                ids.forEach(list::add);
                return list.contains(20L) && !list.contains(10L);
            }));
        }

        @Test
        @DisplayName("should handle invalid ID string gracefully")
        void shouldHandleInvalidId() {
            when(languageRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(idService.nextId()).thenReturn(600L);
            when(languageRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            var incoming = List.of(
                    ResumeProfileRequest.LanguageEntry.builder().id("not-a-number").name("Test").proficiency("B1").build()
            );
            StepVerifier.create(languageService.mergeLanguages(profileId, incoming))
                    .verifyComplete();
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
