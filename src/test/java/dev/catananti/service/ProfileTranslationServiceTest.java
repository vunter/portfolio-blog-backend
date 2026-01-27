package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.dto.ResumeProfileResponse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfileTranslationServiceTest {

    @Mock private TranslationService translationService;

    @InjectMocks
    private ProfileTranslationService profileTranslationService;

    private ResumeProfileResponse testProfile;

    @BeforeEach
    void setUp() {
        testProfile = ResumeProfileResponse.builder()
                .id("1")
                .ownerId("100")
                .fullName("John Doe")
                .title("Software Engineer")
                .email("john@example.com")
                .phone("+1234567890")
                .linkedin("linkedin.com/in/johndoe")
                .github("github.com/johndoe")
                .website("johndoe.dev")
                .location("San Francisco, CA")
                .professionalSummary("Experienced software engineer with 10+ years.")
                .interests("Open source, AI, distributed systems")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ==========================================
    // isAvailable
    // ==========================================
    @Nested
    @DisplayName("isAvailable")
    class IsAvailable {

        @Test
        @DisplayName("Should return true when translation service is available")
        void shouldReturnTrueWhenAvailable() {
            when(translationService.isAvailable()).thenReturn(true);
            assertThat(profileTranslationService.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("Should return false when translation service is not available")
        void shouldReturnFalseWhenNotAvailable() {
            when(translationService.isAvailable()).thenReturn(false);
            assertThat(profileTranslationService.isAvailable()).isFalse();
        }
    }

    // ==========================================
    // translateProfile - root fields
    // ==========================================
    @Nested
    @DisplayName("translateProfile")
    class TranslateProfile {

        @Test
        @DisplayName("Should translate root fields (title, location, summary, interests)")
        void shouldTranslateRootFields() {
            // The 4 root translatable fields
            List<String> expectedTexts = List.of(
                    "Software Engineer", "San Francisco, CA",
                    "Experienced software engineer with 10+ years.",
                    "Open source, AI, distributed systems"
            );
            List<String> translatedTexts = List.of(
                    "Engenheiro de Software", "São Francisco, CA",
                    "Engenheiro de software experiente com mais de 10 anos.",
                    "Código aberto, IA, sistemas distribuídos"
            );

            when(translationService.translateBatch(anyList(), eq("pt")))
                    .thenReturn(Mono.just(translatedTexts));

            StepVerifier.create(profileTranslationService.translateProfile(testProfile, "pt"))
                    .assertNext(result -> {
                        // Translated fields
                        assertThat(result.getTitle()).isEqualTo("Engenheiro de Software");
                        assertThat(result.getLocation()).isEqualTo("São Francisco, CA");
                        assertThat(result.getProfessionalSummary())
                                .isEqualTo("Engenheiro de software experiente com mais de 10 anos.");
                        assertThat(result.getInterests())
                                .isEqualTo("Código aberto, IA, sistemas distribuídos");
                        // NOT translated
                        assertThat(result.getFullName()).isEqualTo("John Doe");
                        assertThat(result.getEmail()).isEqualTo("john@example.com");
                        assertThat(result.getPhone()).isEqualTo("+1234567890");
                        assertThat(result.getLinkedin()).isEqualTo("linkedin.com/in/johndoe");
                        assertThat(result.getGithub()).isEqualTo("github.com/johndoe");
                        assertThat(result.getWebsite()).isEqualTo("johndoe.dev");
                        // IDs preserved
                        assertThat(result.getId()).isEqualTo("1");
                        assertThat(result.getOwnerId()).isEqualTo("100");
                    })
                    .verifyComplete();

            verify(translationService).translateBatch(anyList(), eq("pt"));
        }

        @Test
        @DisplayName("Should translate profile with educations")
        void shouldTranslateProfileWithEducations() {
            testProfile.setEducations(List.of(
                    EducationResponse.builder()
                            .id("e1")
                            .institution("MIT")
                            .location("Cambridge, MA")
                            .degree("Bachelor of Science")
                            .fieldOfStudy("Computer Science")
                            .description("Dean's list")
                            .startDate("September 2015")
                            .endDate("June 2019")
                            .sortOrder(0)
                            .build()
            ));

            // 4 root + 7 education = 11 texts
            List<String> translated = List.of(
                    "Engenheiro de Software", "São Francisco, CA", "Resumo traduzido", "Interesses traduzidos",
                    "MIT", "Cambridge, MA", "Bacharelado em Ciência", "Ciência da Computação",
                    "Lista do reitor", "Setembro 2015", "Junho 2019"
            );

            when(translationService.translateBatch(anyList(), eq("pt")))
                    .thenReturn(Mono.just(translated));

            StepVerifier.create(profileTranslationService.translateProfile(testProfile, "pt"))
                    .assertNext(result -> {
                        assertThat(result.getEducations()).hasSize(1);
                        var edu = result.getEducations().getFirst();
                        assertThat(edu.getInstitution()).isEqualTo("MIT");
                        assertThat(edu.getDegree()).isEqualTo("Bacharelado em Ciência");
                        assertThat(edu.getStartDate()).isEqualTo("Setembro 2015");
                        assertThat(edu.getEndDate()).isEqualTo("Junho 2019");
                        assertThat(edu.getId()).isEqualTo("e1");
                        assertThat(edu.getSortOrder()).isZero();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should translate profile with experiences and bullets")
        void shouldTranslateProfileWithExperiences() {
            testProfile.setExperiences(List.of(
                    ExperienceResponse.builder()
                            .id("x1")
                            .company("Google")
                            .position("Senior Engineer")
                            .startDate("June 2020")
                            .endDate("Present")
                            .bullets(List.of("Led a team of 5", "Reduced latency by 40%"))
                            .sortOrder(0)
                            .build()
            ));

            // 4 root + 3 exp fields + 2 bullets = 9
            List<String> translated = List.of(
                    "Engenheiro de Software", "São Francisco", "Resumo", "Interesses",
                    "Engenheiro Sênior", "Junho 2020", "Atual",
                    "Liderou uma equipe de 5", "Reduziu a latência em 40%"
            );

            when(translationService.translateBatch(anyList(), eq("pt")))
                    .thenReturn(Mono.just(translated));

            StepVerifier.create(profileTranslationService.translateProfile(testProfile, "pt"))
                    .assertNext(result -> {
                        assertThat(result.getExperiences()).hasSize(1);
                        var exp = result.getExperiences().getFirst();
                        assertThat(exp.getCompany()).isEqualTo("Google"); // NOT translated
                        assertThat(exp.getPosition()).isEqualTo("Engenheiro Sênior");
                        assertThat(exp.getStartDate()).isEqualTo("Junho 2020");
                        assertThat(exp.getEndDate()).isEqualTo("Atual");
                        assertThat(exp.getBullets()).containsExactly(
                                "Liderou uma equipe de 5", "Reduziu a latência em 40%");
                        assertThat(exp.getId()).isEqualTo("x1");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should translate profile with skills, languages, and certifications")
        void shouldTranslateSkillsLanguagesCerts() {
            testProfile.setSkills(List.of(
                    SkillResponse.builder().id("s1").category("Backend").content("Java, Spring").sortOrder(0).build()
            ));
            testProfile.setLanguages(List.of(
                    LanguageResponse.builder().id("l1").name("English").proficiency("Native").sortOrder(0).build()
            ));
            testProfile.setCertifications(List.of(
                    CertificationResponse.builder()
                            .id("c1").name("AWS SAA").issuer("Amazon").description("Cloud cert")
                            .issueDate("2025-01").credentialUrl("https://cert.url").sortOrder(0).build()
            ));

            // 4 root + 2 skill + 2 lang + 3 cert = 11
            List<String> translated = List.of(
                    "Engenheiro", "Local", "Resumo", "Interesses",
                    "Back-end", "Java, Spring",
                    "Inglês", "Nativo",
                    "AWS SAA", "Amazon", "Certificação em nuvem"
            );

            when(translationService.translateBatch(anyList(), eq("pt")))
                    .thenReturn(Mono.just(translated));

            StepVerifier.create(profileTranslationService.translateProfile(testProfile, "pt"))
                    .assertNext(result -> {
                        // Skills
                        assertThat(result.getSkills()).hasSize(1);
                        assertThat(result.getSkills().getFirst().getCategory()).isEqualTo("Back-end");
                        assertThat(result.getSkills().getFirst().getId()).isEqualTo("s1");

                        // Languages
                        assertThat(result.getLanguages()).hasSize(1);
                        assertThat(result.getLanguages().getFirst().getName()).isEqualTo("Inglês");
                        assertThat(result.getLanguages().getFirst().getProficiency()).isEqualTo("Nativo");

                        // Certifications
                        assertThat(result.getCertifications()).hasSize(1);
                        assertThat(result.getCertifications().getFirst().getDescription())
                                .isEqualTo("Certificação em nuvem");
                        assertThat(result.getCertifications().getFirst().getIssueDate()).isEqualTo("2025-01");
                        assertThat(result.getCertifications().getFirst().getCredentialUrl()).isEqualTo("https://cert.url");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should translate additionalInfo and homeCustomization")
        void shouldTranslateAdditionalInfoAndHomeCustomization() {
            testProfile.setAdditionalInfo(List.of(
                    AdditionalInfoResponse.builder().id("a1").label("Hobbies").content("Gaming").sortOrder(0).build()
            ));
            testProfile.setHomeCustomization(List.of(
                    HomeCustomizationResponse.builder().id("h1").label("Welcome").content("Hello!").sortOrder(0).build()
            ));

            // 4 root + 2 addInfo + 2 homeCust = 8
            List<String> translated = List.of(
                    "Engenheiro", "Local", "Resumo", "Interesses",
                    "Hobbies", "Jogos",
                    "Bem-vindo", "Olá!"
            );

            when(translationService.translateBatch(anyList(), eq("pt")))
                    .thenReturn(Mono.just(translated));

            StepVerifier.create(profileTranslationService.translateProfile(testProfile, "pt"))
                    .assertNext(result -> {
                        assertThat(result.getAdditionalInfo()).hasSize(1);
                        assertThat(result.getAdditionalInfo().getFirst().getLabel()).isEqualTo("Hobbies");
                        assertThat(result.getAdditionalInfo().getFirst().getContent()).isEqualTo("Jogos");

                        assertThat(result.getHomeCustomization()).hasSize(1);
                        assertThat(result.getHomeCustomization().getFirst().getLabel()).isEqualTo("Bem-vindo");
                        assertThat(result.getHomeCustomization().getFirst().getContent()).isEqualTo("Olá!");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle profile with all null sub-collections")
        void shouldHandleNullSubCollections() {
            testProfile.setEducations(null);
            testProfile.setExperiences(null);
            testProfile.setSkills(null);
            testProfile.setLanguages(null);
            testProfile.setCertifications(null);
            testProfile.setAdditionalInfo(null);
            testProfile.setHomeCustomization(null);

            // Only 4 root fields
            List<String> translated = List.of("Engenheiro", "São Francisco", "Resumo", "Interesses");

            when(translationService.translateBatch(anyList(), eq("pt")))
                    .thenReturn(Mono.just(translated));

            StepVerifier.create(profileTranslationService.translateProfile(testProfile, "pt"))
                    .assertNext(result -> {
                        assertThat(result.getTitle()).isEqualTo("Engenheiro");
                        assertThat(result.getEducations()).isEmpty();
                        assertThat(result.getExperiences()).isEmpty();
                        assertThat(result.getSkills()).isEmpty();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle profile with empty sub-collections")
        void shouldHandleEmptySubCollections() {
            testProfile.setEducations(List.of());
            testProfile.setExperiences(List.of());
            testProfile.setSkills(List.of());
            testProfile.setLanguages(List.of());
            testProfile.setCertifications(List.of());
            testProfile.setAdditionalInfo(List.of());
            testProfile.setHomeCustomization(List.of());

            List<String> translated = List.of("Engenheiro", "São Francisco", "Resumo", "Interesses");

            when(translationService.translateBatch(anyList(), eq("pt")))
                    .thenReturn(Mono.just(translated));

            StepVerifier.create(profileTranslationService.translateProfile(testProfile, "pt"))
                    .assertNext(result -> {
                        assertThat(result.getTitle()).isEqualTo("Engenheiro");
                        assertThat(result.getEducations()).isEmpty();
                        assertThat(result.getExperiences()).isEmpty();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should propagate translation service error")
        void shouldPropagateTranslationError() {
            when(translationService.translateBatch(anyList(), eq("pt")))
                    .thenReturn(Mono.error(new RuntimeException("Translation API unavailable")));

            StepVerifier.create(profileTranslationService.translateProfile(testProfile, "pt"))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    // ==========================================
    // toRequest
    // ==========================================
    @Nested
    @DisplayName("toRequest")
    class ToRequest {

        @Test
        @DisplayName("Should convert response to request preserving all fields")
        void shouldConvertResponseToRequest() {
            testProfile.setEducations(List.of(
                    EducationResponse.builder()
                            .id("e1").institution("MIT").location("Cambridge")
                            .degree("BS").fieldOfStudy("CS").startDate("2015").endDate("2019")
                            .description("Desc").sortOrder(0).build()
            ));
            testProfile.setExperiences(List.of(
                    ExperienceResponse.builder()
                            .id("x1").company("Google").position("SWE")
                            .startDate("2020").endDate("Present")
                            .bullets(List.of("Bullet 1")).sortOrder(0).build()
            ));
            testProfile.setSkills(List.of(
                    SkillResponse.builder().id("s1").category("Backend").content("Java").sortOrder(0).build()
            ));
            testProfile.setLanguages(List.of(
                    LanguageResponse.builder().id("l1").name("English").proficiency("Native").sortOrder(0).build()
            ));
            testProfile.setCertifications(List.of(
                    CertificationResponse.builder()
                            .id("c1").name("AWS").issuer("Amazon").issueDate("2025")
                            .credentialUrl("url").description("desc").sortOrder(0).build()
            ));
            testProfile.setAdditionalInfo(List.of(
                    AdditionalInfoResponse.builder().id("a1").label("L").content("C").sortOrder(0).build()
            ));
            testProfile.setHomeCustomization(List.of(
                    HomeCustomizationResponse.builder().id("h1").label("W").content("H").sortOrder(0).build()
            ));

            ResumeProfileRequest request = profileTranslationService.toRequest(testProfile);

            assertThat(request.getFullName()).isEqualTo("John Doe");
            assertThat(request.getTitle()).isEqualTo("Software Engineer");
            assertThat(request.getEmail()).isEqualTo("john@example.com");
            assertThat(request.getEducations()).hasSize(1);
            assertThat(request.getExperiences()).hasSize(1);
            assertThat(request.getSkills()).hasSize(1);
            assertThat(request.getLanguages()).hasSize(1);
            assertThat(request.getCertifications()).hasSize(1);
            assertThat(request.getAdditionalInfo()).hasSize(1);
            assertThat(request.getHomeCustomization()).hasSize(1);

            // Verify education mapping
            assertThat(request.getEducations().getFirst().getInstitution()).isEqualTo("MIT");
            assertThat(request.getEducations().getFirst().getDegree()).isEqualTo("BS");

            // Verify experience mapping
            assertThat(request.getExperiences().getFirst().getCompany()).isEqualTo("Google");
            assertThat(request.getExperiences().getFirst().getBullets()).containsExactly("Bullet 1");
        }

        @Test
        @DisplayName("Should handle null collections in response")
        void shouldHandleNullCollections() {
            testProfile.setEducations(null);
            testProfile.setExperiences(null);
            testProfile.setSkills(null);
            testProfile.setLanguages(null);
            testProfile.setCertifications(null);
            testProfile.setAdditionalInfo(null);
            testProfile.setHomeCustomization(null);

            ResumeProfileRequest request = profileTranslationService.toRequest(testProfile);

            assertThat(request.getFullName()).isEqualTo("John Doe");
            assertThat(request.getEducations()).isEmpty();
            assertThat(request.getExperiences()).isEmpty();
            assertThat(request.getSkills()).isEmpty();
            assertThat(request.getLanguages()).isEmpty();
            assertThat(request.getCertifications()).isEmpty();
            assertThat(request.getAdditionalInfo()).isEmpty();
            assertThat(request.getHomeCustomization()).isEmpty();
        }
    }
}
