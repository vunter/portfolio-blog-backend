package dev.catananti.controller;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.dto.UserResponse;
import dev.catananti.service.PdfGenerationService;
import dev.catananti.service.ProfileTranslationService;
import dev.catananti.service.PublicResumeService;
import dev.catananti.service.ResumeProfileService;
import dev.catananti.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResumeProfileControllerTest {

    @Mock private ResumeProfileService profileService;
    @Mock private PdfGenerationService pdfGenerationService;
    @Mock private PublicResumeService publicResumeService;
    @Mock private ProfileTranslationService profileTranslationService;
    @Mock private UserService userService;

    @InjectMocks
    private ResumeProfileController controller;

    private Authentication mockAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("user@test.com");
        return auth;
    }

    private void mockUserLookup() {
        UserResponse user = UserResponse.builder().id("1").name("Test User").email("user@test.com").build();
        when(userService.getUserByEmail("user@test.com")).thenReturn(Mono.just(user));
    }

    private ResumeProfileResponse buildProfile() {
        return ResumeProfileResponse.builder()
                .id("1")
                .ownerId("1")
                .locale("en")
                .fullName("John Doe")
                .title("Software Engineer")
                .email("john@test.com")
                .educations(List.of())
                .experiences(List.of())
                .skills(List.of())
                .languages(List.of())
                .certifications(List.of())
                .additionalInfo(List.of())
                .testimonials(List.of())
                .proficiencies(List.of())
                .projects(List.of())
                .learningTopics(List.of())
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/resume/profile")
    class GetProfile {

        @Test
        @DisplayName("Should return existing profile")
        void shouldReturnExistingProfile() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeProfileResponse profile = buildProfile();

            when(profileService.getProfileByOwnerId(1L, "en")).thenReturn(Mono.just(profile));

            StepVerifier.create(controller.getProfile(auth, "en"))
                    .assertNext(result -> {
                        assertThat(result.getFullName()).isEqualTo("John Doe");
                        assertThat(result.getTitle()).isEqualTo("Software Engineer");
                        assertThat(result.getLocale()).isEqualTo("en");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty default profile when none exists")
        void shouldReturnEmptyDefaultProfile() {
            Authentication auth = mockAuth();
            mockUserLookup();

            when(profileService.getProfileByOwnerId(1L, "en")).thenReturn(Mono.empty());

            StepVerifier.create(controller.getProfile(auth, "en"))
                    .assertNext(result -> {
                        assertThat(result.getLocale()).isEqualTo("en");
                        assertThat(result.getEducations()).isEmpty();
                        assertThat(result.getExperiences()).isEmpty();
                        assertThat(result.getSkills()).isEmpty();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/profile/exists")
    class ProfileExists {

        @Test
        @DisplayName("Should return true when profile exists")
        void shouldReturnTrueWhenProfileExists() {
            Authentication auth = mockAuth();
            mockUserLookup();
            when(profileService.profileExists(1L)).thenReturn(Mono.just(true));

            StepVerifier.create(controller.profileExists(auth))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when profile does not exist")
        void shouldReturnFalseWhenProfileDoesNotExist() {
            Authentication auth = mockAuth();
            mockUserLookup();
            when(profileService.profileExists(1L)).thenReturn(Mono.just(false));

            StepVerifier.create(controller.profileExists(auth))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isFalse();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/profile/locales")
    class ListLocales {

        @Test
        @DisplayName("Should return list of available locales")
        void shouldReturnListOfLocales() {
            Authentication auth = mockAuth();
            mockUserLookup();
            when(profileService.listProfileLocales(1L)).thenReturn(Mono.just(List.of("en", "pt-br")));

            StepVerifier.create(controller.listLocales(auth))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).containsExactly("en", "pt-br");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/resume/profile")
    class SaveProfile {

        @Test
        @DisplayName("Should save profile successfully")
        void shouldSaveProfileSuccessfully() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeProfileRequest request = new ResumeProfileRequest();
            ResumeProfileResponse saved = buildProfile();

            when(profileService.saveProfile(eq(1L), any(ResumeProfileRequest.class), eq("en")))
                    .thenReturn(Mono.just(saved));

            StepVerifier.create(controller.saveProfile(auth, request, "en"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getFullName()).isEqualTo("John Doe");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/profile/generate-html")
    class GenerateHtml {

        @Test
        @DisplayName("Should generate HTML resume")
        void shouldGenerateHtmlResume() {
            Authentication auth = mockAuth();
            mockUserLookup();
            String html = "<html><body>Resume</body></html>";
            when(profileService.generateResumeHtml(1L, "en")).thenReturn(Mono.just(html));

            StepVerifier.create(controller.generateHtml(auth, "en"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).contains("Resume");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/profile/download-html")
    class DownloadHtml {

        @Test
        @DisplayName("Should download HTML resume with correct filename")
        void shouldDownloadHtmlResumeWithCorrectFilename() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeProfileResponse profile = buildProfile();
            String html = "<html><body>Resume</body></html>";

            when(profileService.getProfileByOwnerIdWithFallback(1L, "en")).thenReturn(Mono.just(profile));
            when(profileService.generateResumeHtml(1L, "en")).thenReturn(Mono.just(html));

            StepVerifier.create(controller.downloadHtml(auth, "en"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                                .contains("attachment")
                                .contains("john-doe");
                        assertThat(response.getBody()).contains("Resume");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should append pt suffix for Portuguese locale")
        void shouldAppendPtSuffixForPortugueseLocale() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeProfileResponse profile = buildProfile();
            String html = "<html><body>Currículo</body></html>";

            when(profileService.getProfileByOwnerIdWithFallback(1L, "pt-br")).thenReturn(Mono.just(profile));
            when(profileService.generateResumeHtml(1L, "pt-br")).thenReturn(Mono.just(html));

            StepVerifier.create(controller.downloadHtml(auth, "pt-br"))
                    .assertNext(response -> {
                        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                                .contains("-pt.html");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/profile/download-pdf")
    class DownloadPdf {

        @Test
        @DisplayName("Should download PDF resume")
        void shouldDownloadPdfResume() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeProfileResponse profile = buildProfile();
            String html = "<html><body>Resume</body></html>";
            byte[] pdfBytes = new byte[]{1, 2, 3, 4, 5};

            when(profileService.getProfileByOwnerIdWithFallback(1L, "en")).thenReturn(Mono.just(profile));
            when(profileService.generateResumeHtml(1L, "en")).thenReturn(Mono.just(html));
            when(pdfGenerationService.generatePdf(html, "A4", false)).thenReturn(Mono.just(pdfBytes));

            StepVerifier.create(controller.downloadPdf(auth, "en"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                                .contains("john-doe")
                                .contains(".pdf");
                        assertThat(response.getBody()).hasSize(5);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/resume/profile/translate")
    class TranslateProfile {

        @Test
        @DisplayName("Should translate profile successfully")
        void shouldTranslateProfileSuccessfully() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeProfileResponse profile = buildProfile();
            ResumeProfileResponse translated = buildProfile();
            translated.setLocale("pt-br");
            translated.setTitle("Engenheiro de Software");

            when(profileTranslationService.isAvailable()).thenReturn(true);
            when(profileService.getProfileByOwnerIdWithFallback(1L, "en")).thenReturn(Mono.just(profile));
            when(profileTranslationService.translateProfile(profile, "PT-BR")).thenReturn(Mono.just(translated));

            StepVerifier.create(controller.translateProfile(auth, "PT-BR", "en"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody().getTitle()).isEqualTo("Engenheiro de Software");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return 503 when translation service unavailable")
        void shouldReturn503WhenTranslationUnavailable() {
            Authentication auth = mock(Authentication.class);
            when(profileTranslationService.isAvailable()).thenReturn(false);

            StepVerifier.create(controller.translateProfile(auth, "PT-BR", "en"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(503);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/profile/translate/status")
    class TranslationStatus {

        @Test
        @DisplayName("Should return translation status when available")
        void shouldReturnTranslationStatusWhenAvailable() {
            when(profileTranslationService.isAvailable()).thenReturn(true);

            StepVerifier.create(controller.translationStatus())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("available")).isEqualTo(true);
                        assertThat(body.get("provider")).isEqualTo("DeepL");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return translation status when unavailable")
        void shouldReturnTranslationStatusWhenUnavailable() {
            when(profileTranslationService.isAvailable()).thenReturn(false);

            StepVerifier.create(controller.translationStatus())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody().get("available")).isEqualTo(false);
                    })
                    .verifyComplete();
        }
    }
}
