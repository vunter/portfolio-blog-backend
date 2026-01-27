package dev.catananti.service;

import dev.catananti.dto.ResumeProfileResponse;
import dev.catananti.entity.LocalizedText;
import dev.catananti.entity.ResumeTemplate;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ResumeTemplateRepository;
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

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicResumeServiceTest {

    @Mock
    private PdfGenerationService pdfGenerationService;

    @Mock
    private ResumeTemplateRepository resumeTemplateRepository;

    @Mock
    private ResumeProfileService resumeProfileService;

    @InjectMocks
    private PublicResumeService publicResumeService;

    private ResumeTemplate activeTemplate;
    private final Long ownerId = 42L;

    @BeforeEach
    void setUp() {
        LocalizedText name = LocalizedText.ofEnglish("My Resume");

        activeTemplate = ResumeTemplate.builder()
                .id(1L)
                .slug("john-doe")
                .alias("john-doe")
                .name(name)
                .htmlContent("<!doctype html><html><head></head><body><h1>Resume</h1>" + "x".repeat(200) + "</body></html>")
                .cssContent("body { font-size: 14px; }")
                .status("ACTIVE")
                .ownerId(ownerId)
                .build();
    }

    // ============================
    // generateResumePdf
    // ============================
    @Nested
    @DisplayName("generateResumePdf")
    class GenerateResumePdf {

        @Test
        @DisplayName("should generate PDF from template HTML on cache miss")
        void cacheMiss_generatesPdf() {
            byte[] pdfBytes = new byte[]{1, 2, 3, 4, 5};
            when(resumeTemplateRepository.findByAlias("john-doe")).thenReturn(Mono.just(activeTemplate));
            when(pdfGenerationService.generatePdf(anyString(), eq("A4"), eq(false)))
                    .thenReturn(Mono.just(pdfBytes));

            StepVerifier.create(publicResumeService.generateResumePdf("john-doe", "en"))
                    .assertNext(result -> assertThat(result).isEqualTo(pdfBytes))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return cached PDF on second call")
        void cacheHit_returnsCached() {
            byte[] pdfBytes = new byte[]{10, 20, 30};
            when(resumeTemplateRepository.findByAlias("john-doe")).thenReturn(Mono.just(activeTemplate));
            when(pdfGenerationService.generatePdf(anyString(), eq("A4"), eq(false)))
                    .thenReturn(Mono.just(pdfBytes));

            // First call — populates cache
            StepVerifier.create(publicResumeService.generateResumePdf("john-doe", "en"))
                    .expectNext(pdfBytes)
                    .verifyComplete();

            // Second call — should hit cache, no more repo/pdf calls
            StepVerifier.create(publicResumeService.generateResumePdf("john-doe", "en"))
                    .expectNext(pdfBytes)
                    .verifyComplete();

            // PDF generation should have been called only once
            verify(pdfGenerationService, times(1)).generatePdf(anyString(), eq("A4"), eq(false));
        }

        @Test
        @DisplayName("should return error when template not found")
        void templateNotFound_returnsError() {
            when(resumeTemplateRepository.findByAlias("missing")).thenReturn(Mono.empty());
            when(resumeTemplateRepository.findBySlug("missing")).thenReturn(Mono.empty());

            StepVerifier.create(publicResumeService.generateResumePdf("missing", "en"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("should propagate PDF generation error")
        void pdfGenerationError_propagatesError() {
            when(resumeTemplateRepository.findByAlias("john-doe")).thenReturn(Mono.just(activeTemplate));
            when(pdfGenerationService.generatePdf(anyString(), eq("A4"), eq(false)))
                    .thenReturn(Mono.error(new RuntimeException("PDF engine failed")));

            StepVerifier.create(publicResumeService.generateResumePdf("john-doe", "en"))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    // ============================
    // getResumeHtml
    // ============================
    @Nested
    @DisplayName("getResumeHtml")
    class GetResumeHtml {

        @Test
        @DisplayName("should return template HTML when template found with HTML content")
        void templateFoundWithHtml_returnsHtml() {
            when(resumeTemplateRepository.findByAlias("john-doe")).thenReturn(Mono.just(activeTemplate));

            StepVerifier.create(publicResumeService.getResumeHtml("john-doe", "en"))
                    .assertNext(html -> {
                        assertThat(html).contains("<h1>Resume</h1>");
                        assertThat(html.toLowerCase()).contains("<!doctype");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return 404 when template not found")
        void templateNotFound_returns404() {
            when(resumeTemplateRepository.findByAlias("unknown")).thenReturn(Mono.empty());
            when(resumeTemplateRepository.findBySlug("unknown")).thenReturn(Mono.empty());

            StepVerifier.create(publicResumeService.getResumeHtml("unknown", "en"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("should fall back to profile-based generation when template HTML is too short")
        void shortHtml_fallsBackToProfile() {
            ResumeTemplate shortHtmlTemplate = ResumeTemplate.builder()
                    .id(2L)
                    .slug("short")
                    .alias("short")
                    .htmlContent("<p>Tiny</p>") // less than 200 chars
                    .status("ACTIVE")
                    .ownerId(ownerId)
                    .build();
            when(resumeTemplateRepository.findByAlias("short")).thenReturn(Mono.just(shortHtmlTemplate));
            when(resumeProfileService.generateResumeHtml(eq(ownerId), eq("en")))
                    .thenReturn(Mono.just("<html>Profile-based resume</html>"));

            StepVerifier.create(publicResumeService.getResumeHtml("short", "en"))
                    .assertNext(html -> assertThat(html).contains("Profile-based resume"))
                    .verifyComplete();
        }
    }

    // ============================
    // getProfileByAlias
    // ============================
    @Nested
    @DisplayName("getProfileByAlias")
    class GetProfileByAlias {

        @Test
        @DisplayName("should return profile on success")
        void success_returnsProfile() {
            ResumeProfileResponse response = ResumeProfileResponse.builder()
                    .fullName("John Doe")
                    .locale("en")
                    .build();

            when(resumeTemplateRepository.findByAlias("john-doe")).thenReturn(Mono.just(activeTemplate));
            when(resumeProfileService.getProfileByOwnerIdWithFallback(eq(ownerId), eq("en")))
                    .thenReturn(Mono.just(response));

            StepVerifier.create(publicResumeService.getProfileByAlias("john-doe", "en"))
                    .assertNext(profile -> {
                        assertThat(profile.getFullName()).isEqualTo("John Doe");
                        assertThat(profile.getLocale()).isEqualTo("en");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return error when template has no owner")
        void noOwner_returnsError() {
            ResumeTemplate noOwnerTemplate = ResumeTemplate.builder()
                    .id(3L)
                    .slug("orphan")
                    .alias("orphan")
                    .status("ACTIVE")
                    .ownerId(null)
                    .build();
            when(resumeTemplateRepository.findByAlias("orphan")).thenReturn(Mono.just(noOwnerTemplate));

            StepVerifier.create(publicResumeService.getProfileByAlias("orphan", "en"))
                    .expectErrorMatches(e -> e instanceof ResourceNotFoundException
                            && e.getMessage().contains("no owner"))
                    .verify();
        }

        @Test
        @DisplayName("should return error when template not found")
        void notFound_returnsError() {
            when(resumeTemplateRepository.findByAlias("nonexistent")).thenReturn(Mono.empty());
            when(resumeTemplateRepository.findBySlug("nonexistent")).thenReturn(Mono.empty());

            StepVerifier.create(publicResumeService.getProfileByAlias("nonexistent", "en"))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ============================
    // clearPdfCache
    // ============================
    @Nested
    @DisplayName("clearPdfCache")
    class ClearPdfCache {

        @Test
        @DisplayName("should clear cache for a specific alias without error")
        void clearSpecificAlias() {
            // No exceptions expected — just verify it runs
            publicResumeService.clearPdfCache("john-doe");
        }

        @Test
        @DisplayName("should clear all caches when alias is null")
        void clearAll() {
            publicResumeService.clearPdfCache(null);
        }
    }

    // ============================
    // validateLocale (private — tested via reflection)
    // ============================
    @Nested
    @DisplayName("validateLocale")
    class ValidateLocale {

        private String invokeValidateLocale(String lang) throws Exception {
            Method method = PublicResumeService.class.getDeclaredMethod("validateLocale", String.class);
            method.setAccessible(true);
            return (String) method.invoke(publicResumeService, lang);
        }

        @Test
        @DisplayName("should return 'en' for null input")
        void nullInput_returnsEn() throws Exception {
            assertThat(invokeValidateLocale(null)).isEqualTo("en");
        }

        @Test
        @DisplayName("should return 'en' for blank input")
        void blankInput_returnsEn() throws Exception {
            assertThat(invokeValidateLocale("   ")).isEqualTo("en");
        }

        @Test
        @DisplayName("should return supported locale as lowercase")
        void supportedLocale_returnsNormalized() throws Exception {
            assertThat(invokeValidateLocale("EN")).isEqualTo("en");
            assertThat(invokeValidateLocale("pt-br")).isEqualTo("pt-br");
            assertThat(invokeValidateLocale("FR")).isEqualTo("fr");
        }

        @Test
        @DisplayName("should fall back to 'en' for unsupported locale")
        void unsupportedLocale_returnsEn() throws Exception {
            assertThat(invokeValidateLocale("xx")).isEqualTo("en");
            assertThat(invokeValidateLocale("klingon")).isEqualTo("en");
        }

        @Test
        @DisplayName("should match by prefix when full code not found")
        void prefixMatch() throws Exception {
            // 'de-AT' not in the set, but 'de' (prefix) is
            assertThat(invokeValidateLocale("de-AT")).isEqualTo("de");
        }
    }
}
