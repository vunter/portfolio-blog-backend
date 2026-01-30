package dev.catananti.controller;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.PdfGenerationRequest;
import dev.catananti.dto.ResumeTemplateRequest;
import dev.catananti.dto.ResumeTemplateResponse;
import dev.catananti.dto.UserResponse;
import dev.catananti.service.PublicResumeService;
import dev.catananti.service.ResumeProfileService;
import dev.catananti.service.ResumeTemplateService;
import dev.catananti.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResumeTemplateControllerTest {

    @Mock private ResumeTemplateService templateService;
    @Mock private ResumeProfileService profileService;
    @Mock private PublicResumeService publicResumeService;
    @Mock private UserService userService;

    @InjectMocks
    private ResumeTemplateController controller;

    private Authentication mockAuth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("user@test.com");
        return auth;
    }

    private void mockUserLookup() {
        UserResponse user = UserResponse.builder().id("1").name("Test User").email("user@test.com").build();
        when(userService.getUserByEmail("user@test.com")).thenReturn(Mono.just(user));
    }

    private ResumeTemplateResponse buildTemplate(Long id, String name) {
        return ResumeTemplateResponse.builder()
                .id(String.valueOf(id))
                .slug("template-" + id)
                .name(name)
                .htmlContent("<html>Template</html>")
                .cssContent("body { color: #333; }")
                .status("ACTIVE")
                .ownerId("1")
                .version(1)
                .isDefault(false)
                .paperSize("A4")
                .orientation("PORTRAIT")
                .downloadCount(0)
                .build();
    }

    // ==================== Template CRUD ====================

    @Nested
    @DisplayName("POST /api/v1/resume/templates")
    class CreateTemplate {

        @Test
        @DisplayName("Should create a new template")
        void shouldCreateNewTemplate() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeTemplateRequest request = new ResumeTemplateRequest();
            request.setName("My Template");
            request.setHtmlContent("<html>New Template</html>");

            ResumeTemplateResponse created = buildTemplate(1L, "My Template");
            when(templateService.createTemplate(eq(1L), any(ResumeTemplateRequest.class)))
                    .thenReturn(Mono.just(created));

            StepVerifier.create(controller.createTemplate(request, auth))
                    .assertNext(result -> {
                        assertThat(result.getName()).isEqualTo("My Template");
                        assertThat(result.getSlug()).isEqualTo("template-1");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/templates/{id}")
    class GetTemplateById {

        @Test
        @DisplayName("Should return template by ID")
        void shouldReturnTemplateById() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeTemplateResponse template = buildTemplate(1L, "My Template");
            when(templateService.getTemplateById(1L)).thenReturn(Mono.just(template));

            StepVerifier.create(controller.getTemplateById(1L, auth))
                    .assertNext(result -> {
                        assertThat(result.getId()).isEqualTo("1");
                        assertThat(result.getName()).isEqualTo("My Template");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/templates/slug/{slug}")
    class GetTemplateBySlug {

        @Test
        @DisplayName("Should return template by slug")
        void shouldReturnTemplateBySlug() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeTemplateResponse template = buildTemplate(1L, "My Template");
            when(templateService.getTemplateBySlug("template-1")).thenReturn(Mono.just(template));

            StepVerifier.create(controller.getTemplateBySlug("template-1", auth))
                    .assertNext(result -> {
                        assertThat(result.getSlug()).isEqualTo("template-1");
                        assertThat(result.getName()).isEqualTo("My Template");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/resume/templates/{id}")
    class UpdateTemplate {

        @Test
        @DisplayName("Should update template")
        void shouldUpdateTemplate() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeTemplateRequest request = new ResumeTemplateRequest();
            request.setName("Updated Template");

            ResumeTemplateResponse updated = buildTemplate(1L, "Updated Template");
            when(templateService.updateTemplate(eq(1L), eq(1L), any(ResumeTemplateRequest.class)))
                    .thenReturn(Mono.just(updated));

            StepVerifier.create(controller.updateTemplate(1L, request, auth))
                    .assertNext(result -> assertThat(result.getName()).isEqualTo("Updated Template"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/resume/templates/{id}")
    class DeleteTemplate {

        @Test
        @DisplayName("Should delete template")
        void shouldDeleteTemplate() {
            Authentication auth = mockAuth();
            mockUserLookup();
            when(templateService.deleteTemplate(1L, 1L)).thenReturn(Mono.empty());

            StepVerifier.create(controller.deleteTemplate(1L, auth))
                    .verifyComplete();
        }
    }

    // ==================== List Operations ====================

    @Nested
    @DisplayName("GET /api/v1/resume/templates")
    class GetMyTemplates {

        @Test
        @DisplayName("Should return paginated templates")
        void shouldReturnPaginatedTemplates() {
            Authentication auth = mockAuth();
            mockUserLookup();
            PageResponse<ResumeTemplateResponse> page = PageResponse.<ResumeTemplateResponse>builder()
                    .content(List.of(buildTemplate(1L, "T1"), buildTemplate(2L, "T2")))
                    .page(0).size(10).totalElements(2).totalPages(1).first(true).last(true)
                    .build();

            when(templateService.getTemplatesByOwner(1L, 0, 10)).thenReturn(Mono.just(page));

            StepVerifier.create(controller.getMyTemplates(0, 10, auth))
                    .assertNext(result -> {
                        assertThat(result.getContent()).hasSize(2);
                        assertThat(result.getTotalElements()).isEqualTo(2);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/templates/status/{status}")
    class GetTemplatesByStatus {

        @Test
        @DisplayName("Should return templates by status")
        void shouldReturnTemplatesByStatus() {
            Authentication auth = mockAuth();
            mockUserLookup();
            when(templateService.getTemplatesByOwnerAndStatus(1L, "ACTIVE"))
                    .thenReturn(Flux.just(buildTemplate(1L, "Active Template")));

            StepVerifier.create(controller.getTemplatesByStatus("ACTIVE", auth))
                    .assertNext(result -> {
                        assertThat(result).hasSize(1);
                        assertThat(result.getFirst().getStatus()).isEqualTo("ACTIVE");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/templates/search")
    class SearchTemplates {

        @Test
        @DisplayName("Should search templates by query")
        void shouldSearchTemplates() {
            Authentication auth = mockAuth();
            mockUserLookup();
            when(templateService.searchTemplates(1L, "modern"))
                    .thenReturn(Flux.just(buildTemplate(1L, "Modern Resume")));

            StepVerifier.create(controller.searchTemplates("modern", auth))
                    .assertNext(result -> {
                        assertThat(result).hasSize(1);
                        assertThat(result.getFirst().getName()).isEqualTo("Modern Resume");
                    })
                    .verifyComplete();
        }
    }

    // ==================== Duplicate & Preview ====================

    @Nested
    @DisplayName("POST /api/v1/resume/templates/{id}/duplicate")
    class DuplicateTemplate {

        @Test
        @DisplayName("Should duplicate template")
        void shouldDuplicateTemplate() {
            Authentication auth = mockAuth();
            mockUserLookup();

            ResumeTemplateResponse source = buildTemplate(1L, "Original");
            ResumeTemplateResponse copy = buildTemplate(2L, "Original (Copy)");

            when(templateService.getTemplateById(1L)).thenReturn(Mono.just(source));
            when(templateService.createTemplate(eq(1L), any(ResumeTemplateRequest.class)))
                    .thenReturn(Mono.just(copy));

            StepVerifier.create(controller.duplicateTemplate(1L, auth))
                    .assertNext(result -> {
                        assertThat(result.getName()).isEqualTo("Original (Copy)");
                        assertThat(result.getId()).isEqualTo("2");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/templates/{id}/preview")
    class PreviewTemplate {

        @Test
        @DisplayName("Should return HTML preview with CSS")
        void shouldReturnHtmlPreviewWithCss() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeTemplateResponse template = buildTemplate(1L, "Template");
            when(templateService.getTemplateById(1L)).thenReturn(Mono.just(template));

            StepVerifier.create(controller.previewTemplate(1L, auth))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).contains("<style>");
                        assertThat(response.getBody()).contains("<html>Template</html>");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return HTML preview without CSS when CSS is null")
        void shouldReturnHtmlPreviewWithoutCss() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeTemplateResponse template = buildTemplate(1L, "Template");
            template.setCssContent(null);
            when(templateService.getTemplateById(1L)).thenReturn(Mono.just(template));

            StepVerifier.create(controller.previewTemplate(1L, auth))
                    .assertNext(response -> {
                        assertThat(response.getBody()).doesNotContain("<style>");
                        assertThat(response.getBody()).isEqualTo("<html>Template</html>");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/templates/popular")
    class GetPopularTemplates {

        @Test
        @DisplayName("Should return most downloaded templates")
        void shouldReturnPopularTemplates() {
            when(templateService.getMostDownloaded(10))
                    .thenReturn(Flux.just(buildTemplate(1L, "Popular")));

            StepVerifier.create(controller.getPopularTemplates(10))
                    .assertNext(result -> {
                        assertThat(result).hasSize(1);
                        assertThat(result.getFirst().getName()).isEqualTo("Popular");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/templates/default")
    class GetDefaultTemplate {

        @Test
        @DisplayName("Should return default template")
        void shouldReturnDefaultTemplate() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeTemplateResponse def = buildTemplate(1L, "Default Template");
            def.setIsDefault(true);
            when(templateService.getDefaultTemplate(1L)).thenReturn(Mono.just(def));

            StepVerifier.create(controller.getDefaultTemplate(auth))
                    .assertNext(result -> {
                        assertThat(result.getIsDefault()).isTrue();
                        assertThat(result.getName()).isEqualTo("Default Template");
                    })
                    .verifyComplete();
        }
    }

    // ==================== PDF Generation ====================

    @Nested
    @DisplayName("POST /api/v1/resume/templates/{id}/pdf")
    class GeneratePdfFromTemplate {

        @Test
        @DisplayName("Should generate PDF from template")
        void shouldGeneratePdfFromTemplate() {
            Authentication auth = mockAuth();
            mockUserLookup();
            ResumeTemplateResponse template = buildTemplate(1L, "Template");
            when(templateService.getTemplateById(1L)).thenReturn(Mono.just(template));
            byte[] pdfBytes = new byte[]{1, 2, 3};
            when(templateService.generatePdfFromTemplate(eq(1L), any()))
                    .thenReturn(Mono.just(pdfBytes));

            StepVerifier.create(controller.generatePdfFromTemplate(1L, Map.of(), auth))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment");
                        assertThat(response.getBody()).hasSize(3);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/resume/templates/slug/{slug}/pdf")
    class GeneratePdfFromSlug {

        @Test
        @DisplayName("Should generate PDF from slug")
        void shouldGeneratePdfFromSlug() {
            byte[] pdfBytes = new byte[]{4, 5, 6};
            when(templateService.generatePdfFromSlug(eq("my-template"), any()))
                    .thenReturn(Mono.just(pdfBytes));

            StepVerifier.create(controller.generatePdfFromSlug("my-template", null))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("my-template");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/resume/pdf/generate")
    class GeneratePdfFromHtml {

        @Test
        @DisplayName("Should generate PDF from raw HTML")
        void shouldGeneratePdfFromHtml() {
            PdfGenerationRequest request = PdfGenerationRequest.builder()
                    .htmlContent("<html>Content</html>")
                    .filename("my-resume")
                    .build();

            byte[] pdfBytes = new byte[]{7, 8, 9};
            when(templateService.generatePdfFromHtml(request)).thenReturn(Mono.just(pdfBytes));

            StepVerifier.create(controller.generatePdfFromHtml(request))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("my-resume.pdf");
                        assertThat(response.getBody()).hasSize(3);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should use generated filename when not provided")
        void shouldUseGeneratedFilenameWhenNotProvided() {
            PdfGenerationRequest request = PdfGenerationRequest.builder()
                    .htmlContent("<html>Content</html>")
                    .build();

            byte[] pdfBytes = new byte[]{1, 2};
            when(templateService.generatePdfFromHtml(request)).thenReturn(Mono.just(pdfBytes));

            StepVerifier.create(controller.generatePdfFromHtml(request))
                    .assertNext(response -> {
                        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("resume-");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/resume/pdf/preview")
    class PreviewPdf {

        @Test
        @DisplayName("Should return PDF for inline preview")
        void shouldReturnPdfForInlinePreview() {
            PdfGenerationRequest request = PdfGenerationRequest.builder()
                    .htmlContent("<html>Content</html>")
                    .build();

            byte[] pdfBytes = new byte[]{10, 11, 12};
            when(templateService.generatePdfFromHtml(request)).thenReturn(Mono.just(pdfBytes));

            StepVerifier.create(controller.previewPdf(request))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getHeaders().getFirst("Content-Disposition")).isEqualTo("inline");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/css/default")
    class GetDefaultCss {

        @Test
        @DisplayName("Should return default CSS styles")
        void shouldReturnDefaultCss() {
            StepVerifier.create(controller.getDefaultCss())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).contains("Resume Default Styles");
                        assertThat(response.getBody()).contains("font-family");
                    })
                    .verifyComplete();
        }
    }
}
