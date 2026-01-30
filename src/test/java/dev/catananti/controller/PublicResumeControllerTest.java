package dev.catananti.controller;

import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.service.PublicResumeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PublicResumeController using Mockito.
 */
@ExtendWith(MockitoExtension.class)
class PublicResumeControllerTest {

    @Mock
    private PublicResumeService publicResumeService;

    @InjectMocks
    private PublicResumeController controller;

    @Nested
    @DisplayName("GET /api/public/resume/{alias}/pdf")
    class DownloadPdf {

        @Test
        @DisplayName("Should return PDF bytes with correct headers")
        void shouldReturnPdf() {
            byte[] pdfBytes = "fake-pdf-content".getBytes();
            when(publicResumeService.generateResumePdf("leonardo-catananti", "en"))
                    .thenReturn(Mono.just(pdfBytes));

            StepVerifier.create(controller.downloadResumePdf("leonardo-catananti", "en"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isEqualTo(pdfBytes);
                        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                                .isEqualTo("attachment; filename=\"leonardo-catananti-resume-en.pdf\"");
                        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                                .isEqualTo(MediaType.APPLICATION_PDF_VALUE);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should accept lang parameter for Portuguese")
        void shouldAcceptLangParam() {
            byte[] pdfBytes = "fake-pt-pdf".getBytes();
            when(publicResumeService.generateResumePdf("leonardo-catananti", "pt"))
                    .thenReturn(Mono.just(pdfBytes));

            StepVerifier.create(controller.downloadResumePdf("leonardo-catananti", "pt"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                                .isEqualTo("attachment; filename=\"leonardo-catananti-resume-pt.pdf\"");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should propagate error when alias not found")
        void shouldPropagateError_WhenNotFound() {
            when(publicResumeService.generateResumePdf("unknown-alias", "en"))
                    .thenReturn(Mono.error(new ResourceNotFoundException("Resume not found")));

            StepVerifier.create(controller.downloadResumePdf("unknown-alias", "en"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("GET /api/public/resume/{alias}/preview")
    class PreviewPdf {

        @Test
        @DisplayName("Should return inline PDF for preview")
        void shouldReturnInlinePdf() {
            byte[] pdfBytes = "fake-preview-pdf".getBytes();
            when(publicResumeService.generateResumePdf("leonardo-catananti", "en"))
                    .thenReturn(Mono.just(pdfBytes));

            StepVerifier.create(controller.previewResumePdf("leonardo-catananti", "en"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                                .isEqualTo("inline");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/public/resume/{alias}/html")
    class GetHtml {

        @Test
        @DisplayName("Should return HTML content")
        void shouldReturnHtml() {
            String html = "<html><body>Resume</body></html>";
            when(publicResumeService.getResumeHtml("leonardo-catananti", "en"))
                    .thenReturn(Mono.just(html));

            StepVerifier.create(controller.getResumeHtml("leonardo-catananti", "en"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isEqualTo(html);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/public/resume/{alias}/profile")
    class GetProfile {

        @Test
        @DisplayName("Should return profile JSON with default lang")
        void shouldReturnProfileJson() {
            ResumeProfileResponse profile = buildTestProfile();
            when(publicResumeService.getProfileByAlias("leonardo-catananti", "en"))
                    .thenReturn(Mono.just(profile));

            StepVerifier.create(controller.getResumeProfile("leonardo-catananti", "en"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        ResumeProfileResponse body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.getFullName()).isEqualTo("Leonardo Eifert Catananti");
                        assertThat(body.getTitle()).isEqualTo("Senior Software Engineer");
                        assertThat(body.getEmail()).isEqualTo("leonardo.catananti@gmail.com");
                        assertThat(body.getLocale()).isEqualTo("en");
                        assertThat(body.getExperiences()).hasSize(1);
                        assertThat(body.getExperiences().getFirst().getCompany()).isEqualTo("Indeed");
                        assertThat(body.getSkills()).hasSize(1);
                        assertThat(body.getEducations()).hasSize(1);
                        assertThat(body.getLanguages()).hasSize(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should accept lang=pt parameter")
        void shouldAcceptPtLang() {
            ResumeProfileResponse profile = ResumeProfileResponse.builder()
                    .id("1")
                    .ownerId("1")
                    .locale("pt")
                    .fullName("Leonardo Eifert Catananti")
                    .title("Engenheiro de Software Senior")
                    .build();
            when(publicResumeService.getProfileByAlias("leonardo-catananti", "pt"))
                    .thenReturn(Mono.just(profile));

            StepVerifier.create(controller.getResumeProfile("leonardo-catananti", "pt"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody().getLocale()).isEqualTo("pt");
                        assertThat(response.getBody().getTitle()).isEqualTo("Engenheiro de Software Senior");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should propagate error when alias not found")
        void shouldPropagateError_WhenAliasNotFound() {
            when(publicResumeService.getProfileByAlias("unknown-alias", "en"))
                    .thenReturn(Mono.error(new ResourceNotFoundException("Resume not found for alias: unknown-alias")));

            StepVerifier.create(controller.getResumeProfile("unknown-alias", "en"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should propagate error when template has no owner")
        void shouldPropagateError_WhenNoOwner() {
            when(publicResumeService.getProfileByAlias("orphan-template", "en"))
                    .thenReturn(Mono.error(new ResourceNotFoundException("Resume template has no owner: orphan-template")));

            StepVerifier.create(controller.getResumeProfile("orphan-template", "en"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    private ResumeProfileResponse buildTestProfile() {
        return ResumeProfileResponse.builder()
                .id("1")
                .ownerId("1")
                .locale("en")
                .fullName("Leonardo Eifert Catananti")
                .title("Senior Software Engineer")
                .email("leonardo.catananti@gmail.com")
                .location("Curitiba, Brazil")
                .linkedin("linkedin.com/in/leonardo-eifert-catananti")
                .github("github.com/vunter")
                .professionalSummary("Senior Software Engineer with 9+ years of experience.")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .experiences(List.of(
                        ResumeProfileResponse.ExperienceResponse.builder()
                                .id("1")
                                .company("Indeed")
                                .position("Senior Software Engineer")
                                .startDate("June 2024")
                                .bullets(List.of("Lead developer on greenfield project"))
                                .sortOrder(0)
                                .build()
                ))
                .skills(List.of(
                        ResumeProfileResponse.SkillResponse.builder()
                                .id("1")
                                .category("Languages")
                                .content("Java, Go, Python, TypeScript")
                                .sortOrder(0)
                                .build()
                ))
                .educations(List.of(
                        ResumeProfileResponse.EducationResponse.builder()
                                .id("1")
                                .institution("University")
                                .degree("Bachelor")
                                .fieldOfStudy("Computer Science")
                                .sortOrder(0)
                                .build()
                ))
                .languages(List.of(
                        ResumeProfileResponse.LanguageResponse.builder()
                                .id("1")
                                .name("Portuguese")
                                .proficiency("Native")
                                .sortOrder(0)
                                .build()
                ))
                .certifications(List.of())
                .additionalInfo(List.of())
                .build();
    }
}
