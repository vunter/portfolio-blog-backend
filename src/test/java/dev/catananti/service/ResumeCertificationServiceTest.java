package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.ResumeCertification;
import dev.catananti.repository.ResumeCertificationRepository;
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
class ResumeCertificationServiceTest {

    @Mock
    private ResumeCertificationRepository certificationRepository;

    @Mock
    private IdService idService;

    @InjectMocks
    private ResumeCertificationService certificationService;

    private Long profileId;

    @BeforeEach
    void setUp() {
        profileId = 500L;
    }

    // ============================
    // saveCertifications
    // ============================
    @Nested
    @DisplayName("saveCertifications")
    class SaveCertifications {

        @Test
        @DisplayName("should save certification entries successfully")
        void success() {
            var entry = ResumeProfileRequest.CertificationEntry.builder()
                    .name("AWS Solutions Architect")
                    .issuer("Amazon Web Services")
                    .issueDate("2024-03")
                    .credentialUrl("https://aws.amazon.com/verify/12345")
                    .description("Professional level certification")
                    .sortOrder(0)
                    .build();

            when(idService.nextId()).thenReturn(5001L);
            when(certificationRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

            StepVerifier.create(certificationService.saveCertifications(profileId, List.of(entry)))
                    .verifyComplete();

            verify(certificationRepository).saveAll(anyIterable());
        }

        @Test
        @DisplayName("should complete immediately for null list")
        void nullList_completesEmpty() {
            StepVerifier.create(certificationService.saveCertifications(profileId, null))
                    .verifyComplete();

            verifyNoInteractions(certificationRepository);
        }

        @Test
        @DisplayName("should complete immediately for empty list")
        void emptyList_completesEmpty() {
            StepVerifier.create(certificationService.saveCertifications(profileId, List.of()))
                    .verifyComplete();

            verifyNoInteractions(certificationRepository);
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
            when(certificationRepository.deleteByProfileId(profileId)).thenReturn(Mono.empty());

            StepVerifier.create(certificationService.deleteByProfileId(profileId))
                    .verifyComplete();

            verify(certificationRepository).deleteByProfileId(profileId);
        }
    }

    // ============================
    // findByProfileId
    // ============================
    @Nested
    @DisplayName("findByProfileId")
    class FindByProfileId {

        @Test
        @DisplayName("should return mapped certification responses")
        void returnsResults() {
            var entity = ResumeCertification.builder()
                    .id(900L)
                    .profileId(profileId)
                    .name("AWS Solutions Architect")
                    .issuer("Amazon Web Services")
                    .issueDate("2024-03")
                    .credentialUrl("https://aws.amazon.com/verify/12345")
                    .description("Professional level certification")
                    .sortOrder(0)
                    .build();

            when(certificationRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.just(entity));

            StepVerifier.create(certificationService.findByProfileId(profileId))
                    .assertNext(list -> {
                        assertThat(list).hasSize(1);
                        ResumeProfileResponse.CertificationResponse resp = list.getFirst();
                        assertThat(resp.getId()).isEqualTo("900");
                        assertThat(resp.getName()).isEqualTo("AWS Solutions Architect");
                        assertThat(resp.getIssuer()).isEqualTo("Amazon Web Services");
                        assertThat(resp.getIssueDate()).isEqualTo("2024-03");
                        assertThat(resp.getCredentialUrl()).isEqualTo("https://aws.amazon.com/verify/12345");
                        assertThat(resp.getDescription()).isEqualTo("Professional level certification");
                        assertThat(resp.getSortOrder()).isEqualTo(0);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty list when no certifications found")
        void emptyResults() {
            when(certificationRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.empty());

            StepVerifier.create(certificationService.findByProfileId(profileId))
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();
        }
    }
}
