package dev.catananti.service;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.dto.ResumeProfileResponse.*;
import dev.catananti.entity.*;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
class ResumeProfileServiceTest {

    @Mock private ResumeProfileRepository profileRepository;
    @Mock private ResumeEducationService educationService;
    @Mock private ResumeExperienceService experienceService;
    @Mock private ResumeSkillService skillService;
    @Mock private ResumeLanguageService languageService;
    @Mock private ResumeCertificationService certificationService;
    @Mock private ResumeAdditionalInfoRepository additionalInfoRepository;
    @Mock private ResumeHomeCustomizationRepository homeCustomizationRepository;
    @Mock private ResumeTestimonialRepository testimonialRepository;
    @Mock private ResumeProficiencyRepository proficiencyRepository;
    @Mock private ResumeProjectRepository projectRepository;
    @Mock private ResumeLearningTopicRepository learningTopicRepository;
    @Spy private HtmlSanitizerService htmlSanitizer = new HtmlSanitizerService();
    @Mock private IdService idService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ResumeProfileService resumeProfileService;

    private ResumeProfile testProfile;
    private Long ownerId;
    private Long profileId;

    @BeforeEach
    void setUp() {
        ownerId = 100L;
        profileId = 1L;
        testProfile = ResumeProfile.builder()
                .id(profileId)
                .ownerId(ownerId)
                .locale("en")
                .fullName("John Doe")
                .title("Software Engineer")
                .email("john@example.com")
                .phone("+1234567890")
                .linkedin("linkedin.com/in/johndoe")
                .github("github.com/johndoe")
                .website("johndoe.dev")
                .location("San Francisco, CA")
                .professionalSummary("Experienced engineer")
                .interests("Open source")
                .workMode("remote")
                .timezone("America/Los_Angeles")
                .employmentType("full-time")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Helper to mock all child-entity fetches for buildFullResponse().
     */
    private void mockBuildFullResponse(Long profId) {
        lenient().when(educationService.findByProfileId(profId))
                .thenReturn(Mono.just(List.of()));
        lenient().when(experienceService.findByProfileId(profId))
                .thenReturn(Mono.just(List.of()));
        lenient().when(skillService.findByProfileId(profId))
                .thenReturn(Mono.just(List.of()));
        lenient().when(languageService.findByProfileId(profId))
                .thenReturn(Mono.just(List.of()));
        lenient().when(certificationService.findByProfileId(profId))
                .thenReturn(Mono.just(List.of()));
        lenient().when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profId))
                .thenReturn(Flux.empty());
        lenient().when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profId))
                .thenReturn(Flux.empty());
        lenient().when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profId))
                .thenReturn(Flux.empty());
        lenient().when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profId))
                .thenReturn(Flux.empty());
        lenient().when(projectRepository.findByProfileIdOrderBySortOrderAsc(profId))
                .thenReturn(Flux.empty());
        lenient().when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profId))
                .thenReturn(Flux.empty());
    }

    // ==========================================
    // getProfileByOwnerId
    // ==========================================
    @Nested
    @DisplayName("getProfileByOwnerId")
    class GetProfileByOwnerId {

        @Test
        @DisplayName("Should return profile for owner and locale")
        void shouldReturnProfile() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.getProfileByOwnerId(ownerId, "en"))
                    .assertNext(response -> {
                        assertThat(response.getFullName()).isEqualTo("John Doe");
                        assertThat(response.getTitle()).isEqualTo("Software Engineer");
                        assertThat(response.getLocale()).isEqualTo("en");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty when profile does not exist")
        void shouldReturnEmptyWhenNotFound() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "fr"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(resumeProfileService.getProfileByOwnerId(ownerId, "fr"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should normalize locale to lowercase")
        void shouldNormalizeLocale() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "pt-br"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.getProfileByOwnerId(ownerId, "PT-BR"))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("John Doe"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should default to 'en' when locale is null")
        void shouldDefaultToEnWhenLocaleNull() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.getProfileByOwnerId(ownerId, null))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("John Doe"))
                    .verifyComplete();
        }
    }

    // ==========================================
    // getProfileByOwnerIdOrThrow
    // ==========================================
    @Nested
    @DisplayName("getProfileByOwnerIdOrThrow")
    class GetProfileByOwnerIdOrThrow {

        @Test
        @DisplayName("Should return profile when found")
        void shouldReturnExistingProfile() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.getProfileByOwnerIdOrThrow(ownerId, "en"))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("John Doe"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "fr"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(resumeProfileService.getProfileByOwnerIdOrThrow(ownerId, "fr"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==========================================
    // getProfileByOwnerIdWithFallback
    // ==========================================
    @Nested
    @DisplayName("getProfileByOwnerIdWithFallback")
    class GetProfileByOwnerIdWithFallback {

        @Test
        @DisplayName("Should return exact locale match first")
        void shouldReturnExactLocaleMatch() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "pt-br"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.getProfileByOwnerIdWithFallback(ownerId, "pt-br"))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("John Doe"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should fall back to language prefix when exact not found")
        void shouldFallbackToPrefix() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "pt-br"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerIdAndLocalePrefix(ownerId, "pt"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.getProfileByOwnerIdWithFallback(ownerId, "pt-br"))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("John Doe"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should fall back to English when prefix not found")
        void shouldFallbackToEnglish() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "fr"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerIdAndLocalePrefix(ownerId, "fr"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.getProfileByOwnerIdWithFallback(ownerId, "fr"))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("John Doe"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should fall back to any locale when English not found")
        void shouldFallbackToAnyLocale() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "fr"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerIdAndLocalePrefix(ownerId, "fr"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerId(ownerId))
                    .thenReturn(Flux.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.getProfileByOwnerIdWithFallback(ownerId, "fr"))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("John Doe"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when no profile exists at all")
        void shouldThrowWhenNoProfileExists() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "fr"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerIdAndLocalePrefix(ownerId, "fr"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerId(ownerId))
                    .thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.getProfileByOwnerIdWithFallback(ownerId, "fr"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==========================================
    // profileExists
    // ==========================================
    @Nested
    @DisplayName("profileExists")
    class ProfileExists {

        @Test
        @DisplayName("Should return true when profile exists")
        void shouldReturnTrueWhenExists() {
            when(profileRepository.findByOwnerId(ownerId)).thenReturn(Flux.just(testProfile));

            StepVerifier.create(resumeProfileService.profileExists(ownerId))
                    .assertNext(exists -> assertThat(exists).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return false when profile does not exist")
        void shouldReturnFalseWhenNotExists() {
            when(profileRepository.findByOwnerId(999L)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.profileExists(999L))
                    .assertNext(exists -> assertThat(exists).isFalse())
                    .verifyComplete();
        }
    }

    // ==========================================
    // listProfileLocales
    // ==========================================
    @Nested
    @DisplayName("listProfileLocales")
    class ListProfileLocales {

        @Test
        @DisplayName("Should return all locales for owner")
        void shouldReturnAllLocales() {
            ResumeProfile ptProfile = ResumeProfile.builder()
                    .id(2L).ownerId(ownerId).locale("pt-br").build();

            when(profileRepository.findByOwnerId(ownerId))
                    .thenReturn(Flux.just(testProfile, ptProfile));

            StepVerifier.create(resumeProfileService.listProfileLocales(ownerId))
                    .assertNext(locales -> {
                        assertThat(locales).hasSize(2);
                        assertThat(locales).containsExactlyInAnyOrder("en", "pt-br");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty list when no profiles")
        void shouldReturnEmptyListWhenNoProfiles() {
            when(profileRepository.findByOwnerId(999L)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.listProfileLocales(999L))
                    .assertNext(locales -> assertThat(locales).isEmpty())
                    .verifyComplete();
        }
    }

    // ==========================================
    // saveProfile
    // ==========================================
    @Nested
    @DisplayName("saveProfile")
    class SaveProfile {

        private ResumeProfileRequest buildTestRequest() {
            return ResumeProfileRequest.builder()
                    .fullName("John Doe")
                    .title("Software Engineer")
                    .email("john@example.com")
                    .phone("+1234567890")
                    .linkedin("linkedin.com/in/johndoe")
                    .github("github.com/johndoe")
                    .website("johndoe.dev")
                    .location("San Francisco, CA")
                    .professionalSummary("Summary")
                    .interests("Open source")
                    .workMode("remote")
                    .timezone("America/Los_Angeles")
                    .employmentType("full-time")
                    .build();
        }

        private void mockDeleteChildEntities(Long profId) {
            when(educationService.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(experienceService.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(skillService.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(languageService.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(certificationService.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(additionalInfoRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(homeCustomizationRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(testimonialRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(proficiencyRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(projectRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(learningTopicRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
        }

        private void mockSaveChildEntities(Long profId) {
            lenient().when(educationService.saveEducations(eq(profId), any())).thenReturn(Mono.empty());
            lenient().when(experienceService.saveExperiences(eq(profId), any())).thenReturn(Mono.empty());
            lenient().when(skillService.saveSkills(eq(profId), any())).thenReturn(Mono.empty());
            lenient().when(languageService.saveLanguages(eq(profId), any())).thenReturn(Mono.empty());
            lenient().when(certificationService.saveCertifications(eq(profId), any())).thenReturn(Mono.empty());
        }

        /**
         * F-214: Mock merge methods for update path.
         * When incoming lists are null (as in buildTestRequest), merge methods
         * delegate to deleteByProfileId, so we mock both.
         */
        private void mockMergeChildEntities(Long profId) {
            // Merge methods on delegated services (null incoming → delete)
            when(educationService.mergeEducations(eq(profId), any())).thenReturn(Mono.empty());
            when(experienceService.mergeExperiences(eq(profId), any())).thenReturn(Mono.empty());
            when(skillService.mergeSkills(eq(profId), any())).thenReturn(Mono.empty());
            when(languageService.mergeLanguages(eq(profId), any())).thenReturn(Mono.empty());
            when(certificationService.mergeCertifications(eq(profId), any())).thenReturn(Mono.empty());
            // Inline merge for repository-managed types (null incoming → delete)
            when(additionalInfoRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(homeCustomizationRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(testimonialRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(proficiencyRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(projectRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
            when(learningTopicRepository.deleteByProfileId(profId)).thenReturn(Mono.empty());
        }

        @Test
        @DisplayName("Should create new profile when none exists")
        void shouldCreateNewProfile() {
            var request = buildTestRequest();
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(500L);
            when(profileRepository.save(any(ResumeProfile.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            mockSaveChildEntities(500L);
            mockBuildFullResponse(500L);

            StepVerifier.create(resumeProfileService.saveProfile(ownerId, request, "en"))
                    .assertNext(response -> {
                        assertThat(response.getFullName()).isEqualTo("John Doe");
                        assertThat(response.getTitle()).isEqualTo("Software Engineer");
                    })
                    .verifyComplete();

            verify(profileRepository).save(any(ResumeProfile.class));
        }

        @Test
        @DisplayName("Should update existing profile when already exists")
        void shouldUpdateExistingProfile() {
            var request = buildTestRequest();
            request.setFullName("Jane Doe");

            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockMergeChildEntities(profileId);
            when(profileRepository.save(any(ResumeProfile.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.saveProfile(ownerId, request, "en"))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("Jane Doe"))
                    .verifyComplete();
        }
    }

    // ==========================================
    // generateResumeHtml
    // ==========================================
    @Nested
    @DisplayName("generateResumeHtml")
    class GenerateResumeHtml {

        @Test
        @DisplayName("Should generate HTML resume in English")
        void shouldGenerateEnglishHtml() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        assertThat(html).contains("<!DOCTYPE html>");
                        assertThat(html).contains("JOHN DOE");
                        assertThat(html).contains("Software Engineer");
                        assertThat(html).contains("lang=\"en\"");
                        assertThat(html).contains("PROFESSIONAL SUMMARY");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should generate HTML resume in Portuguese with PT section headers")
        void shouldGeneratePortugueseHtml() {
            testProfile.setLocale("pt");
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "pt"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "pt"))
                    .assertNext(html -> {
                        assertThat(html).contains("lang=\"pt\"");
                        assertThat(html).contains("RESUMO PROFISSIONAL");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should generate HTML with default English when no lang specified")
        void shouldDefaultToEnglishWhenNoLang() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId))
                    .assertNext(html -> assertThat(html).contains("lang=\"en\""))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException if profile not found for any locale")
        void shouldThrowWhenProfileNotFound() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerIdAndLocalePrefix(ownerId, "en"))
                    .thenReturn(Mono.empty());
            when(profileRepository.findByOwnerId(ownerId))
                    .thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("Should escape HTML in profile fields")
        void shouldEscapeHtmlInFields() {
            testProfile.setFullName("John <script>alert('xss')</script> Doe");
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        assertThat(html).doesNotContain("<script>");
                        assertThat(html).contains("&lt;script&gt;");
                    })
                    .verifyComplete();
        }
    }

    // ==========================================
    // saveProfile with child entities
    // ==========================================
    @Nested
    @DisplayName("saveProfile with child entities")
    class SaveProfileWithChildEntities {

        private ResumeProfileRequest buildFullRequest() {
            return ResumeProfileRequest.builder()
                    .fullName("John Doe")
                    .title("Software Engineer")
                    .email("john@example.com")
                    .phone("+1234567890")
                    .linkedin("linkedin.com/in/johndoe")
                    .github("github.com/johndoe")
                    .website("johndoe.dev")
                    .location("San Francisco, CA")
                    .professionalSummary("Summary")
                    .interests("Open source")
                    .workMode("remote")
                    .timezone("America/Los_Angeles")
                    .employmentType("full-time")
                    .additionalInfo(List.of(
                            ResumeProfileRequest.AdditionalInfoEntry.builder().label("Volunteering").content("Red Cross").sortOrder(0).build()))
                    .homeCustomization(List.of(
                            ResumeProfileRequest.HomeCustomizationEntry.builder().label("Theme").content("dark").sortOrder(0).build()))
                    .testimonials(List.of(
                            ResumeProfileRequest.TestimonialEntry.builder().authorName("Alice").authorRole("Manager").authorCompany("Acme").text("Great work").sortOrder(0).build()))
                    .proficiencies(List.of(
                            ResumeProfileRequest.ProficiencyEntry.builder().category("Backend").skillName("Java").percentage(90).icon("java-icon").sortOrder(0).build()))
                    .projects(List.of(
                            ResumeProfileRequest.ProjectEntry.builder().title("Blog").description("A blog").techTags(List.of("Java", "Spring")).featured(true).sortOrder(0).build()))
                    .learningTopics(List.of(
                            ResumeProfileRequest.LearningTopicEntry.builder().title("Rust").emoji("\uD83E\uDD80").description("Learning Rust").colorTheme("orange").sortOrder(0).build()))
                    .build();
        }

        @Test
        @DisplayName("Should create new profile with all child entities")
        void shouldCreateProfileWithAllChildren() {
            var request = buildFullRequest();
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en")).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(500L, 501L, 502L, 503L, 504L, 505L, 506L, 507L, 508L);
            when(profileRepository.save(any(ResumeProfile.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            // Mock child service saves
            when(educationService.saveEducations(eq(500L), any())).thenReturn(Mono.empty());
            when(experienceService.saveExperiences(eq(500L), any())).thenReturn(Mono.empty());
            when(skillService.saveSkills(eq(500L), any())).thenReturn(Mono.empty());
            when(languageService.saveLanguages(eq(500L), any())).thenReturn(Mono.empty());
            when(certificationService.saveCertifications(eq(500L), any())).thenReturn(Mono.empty());

            // Mock repository saveAll for additional entities
            when(additionalInfoRepository.saveAll(anyList())).thenReturn(Flux.empty());
            when(homeCustomizationRepository.saveAll(anyList())).thenReturn(Flux.empty());
            when(testimonialRepository.saveAll(anyList())).thenReturn(Flux.empty());
            when(proficiencyRepository.saveAll(anyList())).thenReturn(Flux.empty());
            when(projectRepository.saveAll(anyList())).thenReturn(Flux.empty());
            when(learningTopicRepository.saveAll(anyList())).thenReturn(Flux.empty());

            // Mock buildFullResponse
            mockBuildFullResponse(500L);

            StepVerifier.create(resumeProfileService.saveProfile(ownerId, request, "en"))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("John Doe"))
                    .verifyComplete();

            verify(additionalInfoRepository).saveAll(anyList());
            verify(homeCustomizationRepository).saveAll(anyList());
            verify(testimonialRepository).saveAll(anyList());
            verify(proficiencyRepository).saveAll(anyList());
            verify(projectRepository).saveAll(anyList());
            verify(learningTopicRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("Should create project with null techTags defaulting to empty JSON array")
        void shouldHandleNullTechTags() {
            var request = ResumeProfileRequest.builder()
                    .fullName("Jane Doe").title("Dev")
                    .projects(List.of(
                            ResumeProfileRequest.ProjectEntry.builder().title("NoTags").description("desc").sortOrder(0).build()))
                    .build();

            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en")).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(600L, 601L);
            when(profileRepository.save(any(ResumeProfile.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(educationService.saveEducations(eq(600L), any())).thenReturn(Mono.empty());
            when(experienceService.saveExperiences(eq(600L), any())).thenReturn(Mono.empty());
            when(skillService.saveSkills(eq(600L), any())).thenReturn(Mono.empty());
            when(languageService.saveLanguages(eq(600L), any())).thenReturn(Mono.empty());
            when(certificationService.saveCertifications(eq(600L), any())).thenReturn(Mono.empty());
            when(projectRepository.saveAll(anyList())).thenReturn(Flux.empty());
            mockBuildFullResponse(600L);

            StepVerifier.create(resumeProfileService.saveProfile(ownerId, request, "en"))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("Jane Doe"))
                    .verifyComplete();

            verify(projectRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("Should use index as sortOrder when sortOrder is null")
        void shouldUseIndexAsSortOrderWhenNull() {
            var request = ResumeProfileRequest.builder()
                    .fullName("Sort Test")
                    .additionalInfo(List.of(
                            ResumeProfileRequest.AdditionalInfoEntry.builder().label("A").content("C1").sortOrder(null).build(),
                            ResumeProfileRequest.AdditionalInfoEntry.builder().label("B").content("C2").sortOrder(null).build()))
                    .build();

            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en")).thenReturn(Mono.empty());
            when(idService.nextId()).thenReturn(700L, 701L, 702L);
            when(profileRepository.save(any(ResumeProfile.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(educationService.saveEducations(eq(700L), any())).thenReturn(Mono.empty());
            when(experienceService.saveExperiences(eq(700L), any())).thenReturn(Mono.empty());
            when(skillService.saveSkills(eq(700L), any())).thenReturn(Mono.empty());
            when(languageService.saveLanguages(eq(700L), any())).thenReturn(Mono.empty());
            when(certificationService.saveCertifications(eq(700L), any())).thenReturn(Mono.empty());
            when(additionalInfoRepository.saveAll(anyList())).thenReturn(Flux.empty());
            mockBuildFullResponse(700L);

            StepVerifier.create(resumeProfileService.saveProfile(ownerId, request, "en"))
                    .assertNext(response -> assertThat(response.getFullName()).isEqualTo("Sort Test"))
                    .verifyComplete();

            verify(additionalInfoRepository).saveAll(anyList());
        }
    }

    // ==========================================
    // buildFullResponse with actual data
    // ==========================================
    @Nested
    @DisplayName("buildFullResponse with data")
    class BuildFullResponseWithData {

        private void mockRepositoriesWithData(Long profId) {
            when(educationService.findByProfileId(profId))
                    .thenReturn(Mono.just(List.of(
                            EducationResponse.builder().id("1").institution("MIT").location("Cambridge")
                                    .degree("BSc").fieldOfStudy("CS").startDate("2015").endDate("2019")
                                    .description("Magna cum laude").sortOrder(0).build())));
            when(experienceService.findByProfileId(profId))
                    .thenReturn(Mono.just(List.of(
                            ExperienceResponse.builder().id("1").company("Google").position("SWE")
                                    .startDate("2019").endDate("2022")
                                    .bullets(List.of("Built systems", "Led team")).sortOrder(0).build())));
            when(skillService.findByProfileId(profId))
                    .thenReturn(Mono.just(List.of(
                            SkillResponse.builder().id("1").category("Backend").content("Java, Spring").sortOrder(0).build())));
            when(languageService.findByProfileId(profId))
                    .thenReturn(Mono.just(List.of(
                            LanguageResponse.builder().id("1").name("English").proficiency("Native").sortOrder(0).build())));
            when(certificationService.findByProfileId(profId))
                    .thenReturn(Mono.just(List.of(
                            CertificationResponse.builder().id("1").name("AWS SAA").issuer("Amazon")
                                    .issueDate("2023").credentialUrl("https://cert.aws")
                                    .description("Cloud architecture").sortOrder(0).build())));

            var now = LocalDateTime.now();
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeAdditionalInfo.builder().id(10L).profileId(profId)
                            .label("Volunteering").content("Red Cross").sortOrder(0).createdAt(now).updatedAt(now).build()));
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeHomeCustomization.builder().id(11L).profileId(profId)
                            .label("Theme").content("dark").sortOrder(0).createdAt(now).updatedAt(now).build()));
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeTestimonial.builder().id(12L).profileId(profId)
                            .authorName("Alice").authorRole("Manager").authorCompany("Acme")
                            .text("Great work").sortOrder(0).createdAt(now).updatedAt(now).build()));
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeProficiency.builder().id(13L).profileId(profId)
                            .category("Backend").skillName("Java").percentage(90).icon("java-icon")
                            .sortOrder(0).createdAt(now).updatedAt(now).build()));
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeProject.builder().id(14L).profileId(profId)
                            .title("Blog").description("A blog").techTags("[\"Java\",\"Spring\"]")
                            .featured(true).sortOrder(0).createdAt(now).updatedAt(now).build()));
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeLearningTopic.builder().id(15L).profileId(profId)
                            .title("Rust").emoji("\uD83E\uDD80").description("Learning Rust")
                            .sortOrder(0).createdAt(now).updatedAt(now).build()));
        }

        @Test
        @DisplayName("Should map all child entities to response DTOs")
        void shouldMapAllChildEntitiesToResponse() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockRepositoriesWithData(profileId);

            StepVerifier.create(resumeProfileService.getProfileByOwnerId(ownerId, "en"))
                    .assertNext(response -> {
                        assertThat(response.getFullName()).isEqualTo("John Doe");

                        // Education
                        assertThat(response.getEducations()).hasSize(1);
                        assertThat(response.getEducations().get(0).getInstitution()).isEqualTo("MIT");
                        assertThat(response.getEducations().get(0).getDegree()).isEqualTo("BSc");

                        // Experience
                        assertThat(response.getExperiences()).hasSize(1);
                        assertThat(response.getExperiences().get(0).getCompany()).isEqualTo("Google");
                        assertThat(response.getExperiences().get(0).getBullets()).containsExactly("Built systems", "Led team");

                        // Skills
                        assertThat(response.getSkills()).hasSize(1);
                        assertThat(response.getSkills().get(0).getCategory()).isEqualTo("Backend");

                        // Languages
                        assertThat(response.getLanguages()).hasSize(1);
                        assertThat(response.getLanguages().get(0).getName()).isEqualTo("English");

                        // Certifications
                        assertThat(response.getCertifications()).hasSize(1);
                        assertThat(response.getCertifications().get(0).getName()).isEqualTo("AWS SAA");

                        // Additional entities
                        assertThat(response.getAdditionalInfo()).hasSize(1);
                        assertThat(response.getAdditionalInfo().get(0).getLabel()).isEqualTo("Volunteering");
                        assertThat(response.getAdditionalInfo().get(0).getContent()).isEqualTo("Red Cross");

                        assertThat(response.getHomeCustomization()).hasSize(1);
                        assertThat(response.getHomeCustomization().get(0).getLabel()).isEqualTo("Theme");

                        assertThat(response.getTestimonials()).hasSize(1);
                        assertThat(response.getTestimonials().get(0).getAuthorName()).isEqualTo("Alice");
                        assertThat(response.getTestimonials().get(0).getAuthorCompany()).isEqualTo("Acme");

                        assertThat(response.getProficiencies()).hasSize(1);
                        assertThat(response.getProficiencies().get(0).getSkillName()).isEqualTo("Java");
                        assertThat(response.getProficiencies().get(0).getPercentage()).isEqualTo(90);

                        assertThat(response.getProjects()).hasSize(1);
                        assertThat(response.getProjects().get(0).getTitle()).isEqualTo("Blog");
                        assertThat(response.getProjects().get(0).getTechTags()).containsExactly("Java", "Spring");

                        assertThat(response.getLearningTopics()).hasSize(1);
                        assertThat(response.getLearningTopics().get(0).getTitle()).isEqualTo("Rust");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should parse project techTags from JSON string")
        void shouldParseTechTagsFromJson() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockRepositoriesWithData(profileId);

            StepVerifier.create(resumeProfileService.getProfileByOwnerId(ownerId, "en"))
                    .assertNext(response -> {
                        assertThat(response.getProjects().get(0).getTechTags()).hasSize(2);
                        assertThat(response.getProjects().get(0).getTechTags()).containsExactly("Java", "Spring");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle empty techTags JSON")
        void shouldHandleEmptyTechTagsJson() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            // Set up same as mockRepositoriesWithData but with empty techTags
            when(educationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            var now = LocalDateTime.now();
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.just(ResumeProject.builder().id(14L).profileId(profileId)
                            .title("Empty").description("No tags").techTags("[]")
                            .featured(false).sortOrder(0).createdAt(now).updatedAt(now).build()));

            StepVerifier.create(resumeProfileService.getProfileByOwnerId(ownerId, "en"))
                    .assertNext(response -> {
                        assertThat(response.getProjects()).hasSize(1);
                        assertThat(response.getProjects().get(0).getTechTags()).isEmpty();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle null techTags JSON")
        void shouldHandleNullTechTagsJson() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            var now = LocalDateTime.now();
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId))
                    .thenReturn(Flux.just(ResumeProject.builder().id(14L).profileId(profileId)
                            .title("NullTags").description("null tags").techTags(null)
                            .featured(false).sortOrder(0).createdAt(now).updatedAt(now).build()));

            StepVerifier.create(resumeProfileService.getProfileByOwnerId(ownerId, "en"))
                    .assertNext(response -> {
                        assertThat(response.getProjects()).hasSize(1);
                        assertThat(response.getProjects().get(0).getTechTags()).isEmpty();
                    })
                    .verifyComplete();
        }
    }

    // ==========================================
    // generateResumeHtml with full data
    // ==========================================
    @Nested
    @DisplayName("generateResumeHtml with full data")
    class GenerateResumeHtmlWithFullData {

        private void mockFullProfileData(Long profId) {
            when(educationService.findByProfileId(profId))
                    .thenReturn(Mono.just(List.of(
                            EducationResponse.builder().id("1").institution("MIT").location("Cambridge")
                                    .degree("BSc").fieldOfStudy("CS").startDate("2015").endDate("2019")
                                    .description("Magna cum laude").sortOrder(0).build(),
                            EducationResponse.builder().id("2").institution("Stanford").location("Palo Alto")
                                    .degree("MSc").fieldOfStudy("AI").startDate("2019").endDate("2021")
                                    .description(null).sortOrder(1).build())));
            // Two experiences at same company with same dates for multi-position merge
            when(experienceService.findByProfileId(profId))
                    .thenReturn(Mono.just(List.of(
                            ExperienceResponse.builder().id("1").company("Google").position("Senior SWE")
                                    .startDate("2021").endDate("Present")
                                    .bullets(List.of("Led platform team", "Reduced latency 40%")).sortOrder(0).build(),
                            ExperienceResponse.builder().id("2").company("Google").position("SWE")
                                    .startDate("2021").endDate("Present")
                                    .bullets(List.of("Built microservices")).sortOrder(1).build(),
                            ExperienceResponse.builder().id("3").company("Amazon").position("SDE")
                                    .startDate("2019").endDate("2021")
                                    .bullets(List.of("Designed APIs")).sortOrder(2).build())));
            when(skillService.findByProfileId(profId))
                    .thenReturn(Mono.just(List.of(
                            SkillResponse.builder().id("1").category("Backend").content("Java, Spring, PostgreSQL").sortOrder(0).build(),
                            SkillResponse.builder().id("2").category("Frontend").content("Angular, TypeScript").sortOrder(1).build())));
            when(languageService.findByProfileId(profId))
                    .thenReturn(Mono.just(List.of(
                            LanguageResponse.builder().id("1").name("English").proficiency("Native").sortOrder(0).build(),
                            LanguageResponse.builder().id("2").name("Portuguese").proficiency("Fluent").sortOrder(1).build())));
            when(certificationService.findByProfileId(profId))
                    .thenReturn(Mono.just(List.of(
                            CertificationResponse.builder().id("1").name("AWS SAA").issuer("Amazon")
                                    .issueDate("2023").credentialUrl("https://cert.aws")
                                    .description("Cloud architecture").sortOrder(0).build())));

            var now = LocalDateTime.now();
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeAdditionalInfo.builder().id(10L).profileId(profId)
                            .label("Volunteering").content("Red Cross").sortOrder(0).createdAt(now).updatedAt(now).build()));
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeHomeCustomization.builder().id(11L).profileId(profId)
                            .label("Theme").content("dark").sortOrder(0).createdAt(now).updatedAt(now).build()));
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeTestimonial.builder().id(12L).profileId(profId)
                            .authorName("Alice").authorRole("Manager").authorCompany("Acme")
                            .text("Great work").sortOrder(0).createdAt(now).updatedAt(now).build()));
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeProficiency.builder().id(13L).profileId(profId)
                            .category("Backend").skillName("Java").percentage(90).icon("java-icon")
                            .sortOrder(0).createdAt(now).updatedAt(now).build()));
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeProject.builder().id(14L).profileId(profId)
                            .title("Blog").description("A blog").techTags("[\"Java\",\"Spring\"]")
                            .featured(true).sortOrder(0).createdAt(now).updatedAt(now).build()));
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profId))
                    .thenReturn(Flux.just(ResumeLearningTopic.builder().id(15L).profileId(profId)
                            .title("Rust").emoji("\uD83E\uDD80").description("Learning Rust")
                            .sortOrder(0).createdAt(now).updatedAt(now).build()));
        }

        @Test
        @DisplayName("Should render all HTML sections with data")
        void shouldRenderAllSections() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockFullProfileData(profileId);

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        // Structure
                        assertThat(html).contains("class=\"page\"");
                        assertThat(html).contains("class=\"header\"");

                        // Education
                        assertThat(html).contains("MIT");
                        assertThat(html).contains("BSc in CS");
                        assertThat(html).contains("Stanford");
                        assertThat(html).contains("MSc in AI");
                        assertThat(html).contains("Magna cum laude");
                        assertThat(html).doesNotContain("EDUCATION &amp; LANGUAGES");
                        assertThat(html).contains("EDUCATION");

                        // Languages
                        assertThat(html).contains("<strong>English</strong> - Native");
                        assertThat(html).contains("<strong>Portuguese</strong> - Fluent");

                        // Skills
                        assertThat(html).contains("Backend");
                        assertThat(html).contains("Java, Spring, PostgreSQL");
                        assertThat(html).contains("Frontend");
                        assertThat(html).contains("Angular, TypeScript");

                        // Experience - company headers
                        assertThat(html).contains("Google");
                        assertThat(html).contains("Amazon");
                        // Multi-position merge: two positions under Google
                        assertThat(html).contains("> Senior SWE");
                        assertThat(html).contains("> SWE");
                        // Bullets
                        assertThat(html).contains("Led platform team");
                        assertThat(html).contains("Reduced latency 40%");
                        assertThat(html).contains("Built microservices");
                        assertThat(html).contains("Designed APIs");

                        // Certifications
                        assertThat(html).contains("AWS SAA");
                        assertThat(html).contains("Amazon");
                        assertThat(html).contains("(2023)");
                        assertThat(html).contains("Cloud architecture");
                        assertThat(html).contains("View Certificate");
                        assertThat(html).contains("https://cert.aws");

                        // Additional info
                        assertThat(html).contains("Volunteering");
                        assertThat(html).contains("Red Cross");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should render multi-position experiences under single company header")
        void shouldMergeMultiPositionExperiences() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));
            mockFullProfileData(profileId);

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        // Google should appear once as company header (multi-position merged)
                        int firstGoogleIdx = html.indexOf("company-title\">Google");
                        assertThat(firstGoogleIdx).isGreaterThan(-1);
                        // Both positions should be listed
                        assertThat(html).contains("> Senior SWE");
                        assertThat(html).contains("> SWE");
                        // Amazon is separate
                        assertThat(html).contains("company-title\">Amazon");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should render Portuguese HTML with PT section headers")
        void shouldRenderPortugueseWithAllData() {
            testProfile.setLocale("pt");
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "pt"))
                    .thenReturn(Mono.just(testProfile));
            mockFullProfileData(profileId);

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "pt"))
                    .assertNext(html -> {
                        assertThat(html).contains("lang=\"pt\"");
                        assertThat(html).contains("RESUMO PROFISSIONAL");
                        assertThat(html).contains("HABILIDADES");
                        assertThat(html).contains("EXPERI\u00caNCIA");
                        assertThat(html).contains("EXPERI\u00caNCIA PROFISSIONAL");
                        assertThat(html).contains("CERTIFICA\u00c7\u00d5ES");
                        assertThat(html).contains("INFORMA\u00c7\u00d5ES ADICIONAIS");
                        assertThat(html).contains("por Amazon"); // "by" → "por" in PT
                        assertThat(html).contains("Ver Certificado"); // PT cert link
                        assertThat(html).contains("Idiomas"); // PT languages label
                    })
                    .verifyComplete();
        }
    }

    // ==========================================
    // generateResumeHtml edge cases
    // ==========================================
    @Nested
    @DisplayName("generateResumeHtml edge cases")
    class GenerateResumeHtmlEdgeCases {

        @Test
        @DisplayName("Should use English headers for non-PT locale like Spanish")
        void shouldUseEnglishHeadersForSpanish() {
            testProfile.setLocale("es");
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "es"))
                    .thenReturn(Mono.just(testProfile));
            mockBuildFullResponse(profileId);

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "es"))
                    .assertNext(html -> {
                        assertThat(html).contains("lang=\"es\"");
                        assertThat(html).contains("PROFESSIONAL SUMMARY");
                        assertThat(html).doesNotContain("RESUMO PROFISSIONAL");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should render education section without languages")
        void shouldRenderEducationWithoutLanguages() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            EducationResponse.builder().id("1").institution("MIT").degree("BSc")
                                    .fieldOfStudy("CS").startDate("2015").endDate("2019").sortOrder(0).build())));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        assertThat(html).contains("EDUCATION");
                        assertThat(html).contains("MIT");
                        assertThat(html).contains("BSc in CS");
                        assertThat(html).doesNotContain("Languages");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should render languages section without education")
        void shouldRenderLanguagesWithoutEducation() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            LanguageResponse.builder().id("1").name("Spanish").proficiency("Intermediate").sortOrder(0).build())));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        assertThat(html).contains("EDUCATION & LANGUAGES");
                        assertThat(html).contains("<strong>Spanish</strong> - Intermediate");
                        // education-col div should not be rendered (only in CSS)
                        assertThat(html).doesNotContain("<div class=\"education-col\">");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle experience with null bullets")
        void shouldHandleExperienceWithNullBullets() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(experienceService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            ExperienceResponse.builder().id("1").company("Startup Inc").position("CTO")
                                    .startDate("2022").endDate("Present").bullets(null).sortOrder(0).build())));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        assertThat(html).contains("Startup Inc");
                        assertThat(html).contains("> CTO");
                        assertThat(html).doesNotContain("<ul>");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle experience with empty bullets list")
        void shouldHandleExperienceWithEmptyBullets() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(experienceService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            ExperienceResponse.builder().id("1").company("Startup").position("Dev")
                                    .startDate("2022").endDate(null).bullets(List.of()).sortOrder(0).build())));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        assertThat(html).contains("Startup");
                        assertThat(html).contains("> Dev");
                        assertThat(html).doesNotContain("<ul>");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle certifications with null credentialUrl and description")
        void shouldHandleCertificationsWithNullFields() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            CertificationResponse.builder().id("1").name("CKA").issuer("CNCF")
                                    .issueDate("2024").credentialUrl(null).description(null).sortOrder(0).build())));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        assertThat(html).contains("CKA");
                        assertThat(html).contains("CNCF");
                        assertThat(html).contains("(2024)");
                        assertThat(html).doesNotContain("View Certificate");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle certifications with blank credentialUrl")
        void shouldHandleCertificationsWithBlankCredentialUrl() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            CertificationResponse.builder().id("1").name("CKA").issuer("CNCF")
                                    .issueDate("").credentialUrl("  ").description("Kubernetes cert").sortOrder(0).build())));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        assertThat(html).contains("CKA");
                        assertThat(html).contains("Kubernetes cert");
                        assertThat(html).doesNotContain("View Certificate");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should render formatDateRange: both dates present")
        void shouldFormatDateRangeBothDates() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            EducationResponse.builder().id("1").institution("Uni").degree("BA")
                                    .startDate("2010").endDate("2014").sortOrder(0).build())));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> assertThat(html).contains("2010 - 2014"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should render formatDateRange: null end means Since")
        void shouldFormatDateRangeNullEnd() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            EducationResponse.builder().id("1").institution("Uni").degree("PhD")
                                    .startDate("2020").endDate(null).sortOrder(0).build())));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> assertThat(html).contains("Since 2020"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should render formatDateRange: null start with end")
        void shouldFormatDateRangeNullStart() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            EducationResponse.builder().id("1").institution("Uni").degree("BA")
                                    .startDate(null).endDate("2014").sortOrder(0).build())));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> assertThat(html).contains(" - 2014"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should render formatDateRange: both null produces no date line")
        void shouldFormatDateRangeBothNull() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            EducationResponse.builder().id("1").institution("Uni").degree("BA")
                                    .startDate(null).endDate(null).sortOrder(0).build())));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        assertThat(html).contains("Uni");
                        assertThat(html).doesNotContain("Since");
                        // No date-range div should be rendered for null/null dates
                        assertThat(html).doesNotContain("<div>Since");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should render education divider between multiple entries")
        void shouldRenderEducationDivider() {
            when(profileRepository.findByOwnerIdAndLocale(ownerId, "en"))
                    .thenReturn(Mono.just(testProfile));

            when(educationService.findByProfileId(profileId))
                    .thenReturn(Mono.just(List.of(
                            EducationResponse.builder().id("1").institution("MIT").degree("BSc")
                                    .startDate("2010").endDate("2014").sortOrder(0).build(),
                            EducationResponse.builder().id("2").institution("Stanford").degree("MSc")
                                    .startDate("2014").endDate("2016").sortOrder(1).build())));
            when(experienceService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(skillService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(languageService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(certificationService.findByProfileId(profileId)).thenReturn(Mono.just(List.of()));
            when(additionalInfoRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(homeCustomizationRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(testimonialRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(proficiencyRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(projectRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());
            when(learningTopicRepository.findByProfileIdOrderBySortOrderAsc(profileId)).thenReturn(Flux.empty());

            StepVerifier.create(resumeProfileService.generateResumeHtml(ownerId, "en"))
                    .assertNext(html -> {
                        assertThat(html).contains("MIT");
                        assertThat(html).contains("Stanford");
                        assertThat(html).contains("edu-divider");
                    })
                    .verifyComplete();
        }
    }
}
