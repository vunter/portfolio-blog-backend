package dev.catananti.service;

import dev.catananti.dto.PageResponse;
import dev.catananti.dto.ResumeTemplateRequest;
import dev.catananti.dto.ResumeTemplateResponse;
import dev.catananti.entity.ResumeTemplate;
import dev.catananti.entity.User;
import dev.catananti.exception.ResourceNotFoundException;
import dev.catananti.repository.ResumeTemplateRepository;
import dev.catananti.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import dev.catananti.entity.LocalizedText;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeTemplateService Tests")
class ResumeTemplateServiceTest {

    @InjectMocks
    private ResumeTemplateService templateService;

    @Mock
    private ResumeTemplateRepository templateRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PdfGenerationService pdfGenerationService;

    @Mock
    private IdService idService;

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private org.springframework.core.env.Environment environment;

    private ResumeTemplate sampleTemplate;
    private User sampleUser;

    @BeforeEach
    void setUp() {
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        sampleUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .name("Test User")
                .build();

        sampleTemplate = ResumeTemplate.builder()
                .id(100L)
                .slug("my-resume")
                .name(LocalizedText.of("en", "My Resume"))
                .description(LocalizedText.of("en", "A professional resume template"))
                .htmlContent("<html><body><h1>Resume</h1></body></html>")
                .status("ACTIVE")
                .ownerId(1L)
                .version(1)
                .isDefault(false)
                .paperSize("A4")
                .orientation("PORTRAIT")
                .downloadCount(5)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Should get template by ID")
    void shouldGetTemplateById() {
        when(templateRepository.findById(100L)).thenReturn(Mono.just(sampleTemplate));
        when(userRepository.findById(1L)).thenReturn(Mono.just(sampleUser));

        StepVerifier.create(templateService.getTemplateById(100L))
                .assertNext(response -> {
                    assertThat(response.getId()).isEqualTo(String.valueOf(100L));
                    assertThat(response.getSlug()).isEqualTo("my-resume");
                    assertThat(response.getName()).isEqualTo("My Resume");
                    assertThat(response.getOwnerName()).isEqualTo("Test User");
                })
                .verifyComplete();

        verify(templateRepository).findById(100L);
    }

    @Test
    @DisplayName("Should throw when template not found by ID")
    void shouldThrowWhenTemplateNotFoundById() {
        when(templateRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(templateService.getTemplateById(999L))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Should get template by slug")
    void shouldGetTemplateBySlug() {
        when(templateRepository.findBySlug("my-resume")).thenReturn(Mono.just(sampleTemplate));
        when(userRepository.findById(1L)).thenReturn(Mono.just(sampleUser));

        StepVerifier.create(templateService.getTemplateBySlug("my-resume"))
                .assertNext(response -> {
                    assertThat(response.getSlug()).isEqualTo("my-resume");
                    assertThat(response.getName()).isEqualTo("My Resume");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should create template")
    void shouldCreateTemplate() {
        ResumeTemplateRequest request = ResumeTemplateRequest.builder()
                .name("New Template")
                .description("A new template")
                .htmlContent("<html><body><h1>New</h1></body></html>")
                .status("DRAFT")
                .paperSize("A4")
                .build();

        when(idService.nextId()).thenReturn(200L);
        when(pdfGenerationService.validateHtml(anyString())).thenReturn(Mono.just(true));
        when(templateRepository.existsBySlug(anyString())).thenReturn(Mono.just(false));
        when(templateRepository.save(any(ResumeTemplate.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(userRepository.findById(1L)).thenReturn(Mono.just(sampleUser));

        StepVerifier.create(templateService.createTemplate(1L, request))
                .assertNext(response -> {
                    assertThat(response.getName()).isEqualTo("New Template");
                    assertThat(response.getSlug()).isEqualTo("new-template");
                    assertThat(response.getStatus()).isEqualTo("DRAFT");
                })
                .verifyComplete();

        verify(templateRepository).save(any(ResumeTemplate.class));
    }

    @Test
    @DisplayName("Should reject invalid HTML content")
    void shouldRejectInvalidHtmlContent() {
        // Empty HTML content is rejected before calling validateHtml
        ResumeTemplateRequest request = ResumeTemplateRequest.builder()
                .name("Invalid Template")
                .htmlContent("")
                .build();

        StepVerifier.create(templateService.createTemplate(1L, request))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should update template")
    void shouldUpdateTemplate() {
        ResumeTemplateRequest request = ResumeTemplateRequest.builder()
                .name("Updated Name")
                .description("Updated description")
                .build();

        when(templateRepository.findById(100L)).thenReturn(Mono.just(sampleTemplate));
        when(userRepository.findById(1L)).thenReturn(Mono.just(sampleUser));
        
        // Mock DatabaseClient chain for UPDATE
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        DatabaseClient.GenericExecuteSpec boundSpec = mock(DatabaseClient.GenericExecuteSpec.class, withSettings().lenient());
        @SuppressWarnings("unchecked")
        org.springframework.r2dbc.core.FetchSpec<Map<String, Object>> fetchSpec = mock(org.springframework.r2dbc.core.FetchSpec.class);
        
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.bindNull(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));

        StepVerifier.create(templateService.updateTemplate(100L, 1L, request))
                .assertNext(response -> {
                    assertThat(response.getName()).isEqualTo("Updated Name");
                    assertThat(response.getDescription()).isEqualTo("Updated description");
                    assertThat(response.getVersion()).isEqualTo(2); // Version incremented
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject update by non-owner")
    void shouldRejectUpdateByNonOwner() {
        ResumeTemplateRequest request = ResumeTemplateRequest.builder()
                .name("Updated Name")
                .build();

        when(templateRepository.findById(100L)).thenReturn(Mono.just(sampleTemplate));

        StepVerifier.create(templateService.updateTemplate(100L, 999L, request)) // Different owner
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should delete template")
    void shouldDeleteTemplate() {
        DatabaseClient.GenericExecuteSpec executeSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        DatabaseClient.GenericExecuteSpec boundSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        @SuppressWarnings("unchecked")
        org.springframework.r2dbc.core.FetchSpec<Map<String, Object>> fetchSpec = mock(org.springframework.r2dbc.core.FetchSpec.class);
        
        when(templateRepository.findById(100L)).thenReturn(Mono.just(sampleTemplate));
        when(databaseClient.sql("DELETE FROM resume_templates WHERE id = :id")).thenReturn(executeSpec);
        when(executeSpec.bind("id", 100L)).thenReturn(boundSpec);
        when(boundSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(1L));

        StepVerifier.create(templateService.deleteTemplate(100L, 1L))
                .assertNext(result -> assertThat(result).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reject delete by non-owner")
    void shouldRejectDeleteByNonOwner() {
        when(templateRepository.findById(100L)).thenReturn(Mono.just(sampleTemplate));

        StepVerifier.create(templateService.deleteTemplate(100L, 999L))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(templateRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Should get templates by owner with pagination")
    void shouldGetTemplatesByOwnerPaginated() {
        when(templateRepository.findByOwnerIdPaginated(1L, 10, 0))
                .thenReturn(Flux.just(sampleTemplate));
        when(templateRepository.countByOwnerId(1L)).thenReturn(Mono.just(1L));
        when(userRepository.findById(1L)).thenReturn(Mono.just(sampleUser));

        StepVerifier.create(templateService.getTemplatesByOwner(1L, 0, 10))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(1);
                    assertThat(page.getPage()).isZero();
                    assertThat(page.getTotalElements()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search templates by name")
    void shouldSearchTemplatesByName() {
        when(templateRepository.searchByName(1L, "resume"))
                .thenReturn(Flux.just(sampleTemplate));
        when(userRepository.findById(1L)).thenReturn(Mono.just(sampleUser));

        StepVerifier.create(templateService.searchTemplates(1L, "resume").collectList())
                .assertNext(list -> {
                    assertThat(list).hasSize(1);
                    assertThat(list.get(0).getName()).contains("Resume");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should generate PDF from template")
    void shouldGeneratePdfFromTemplate() {
        byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46}; // %PDF

        when(templateRepository.findById(100L)).thenReturn(Mono.just(sampleTemplate));
        when(templateRepository.incrementDownloadCount(100L)).thenReturn(Mono.empty());
        when(pdfGenerationService.generatePdfWithVariables(
                anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(Mono.just(pdfBytes));

        StepVerifier.create(templateService.generatePdfFromTemplate(100L, Map.of("name", "John")))
                .assertNext(bytes -> {
                    assertThat(bytes).isEqualTo(pdfBytes);
                })
                .verifyComplete();

        verify(templateRepository).incrementDownloadCount(100L);
    }

    @Test
    @DisplayName("Should generate PDF from template slug")
    void shouldGeneratePdfFromSlug() {
        byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46};

        when(templateRepository.findBySlug("my-resume")).thenReturn(Mono.just(sampleTemplate));
        when(templateRepository.findById(100L)).thenReturn(Mono.just(sampleTemplate));
        when(templateRepository.incrementDownloadCount(100L)).thenReturn(Mono.empty());
        when(pdfGenerationService.generatePdfWithVariables(anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(Mono.just(pdfBytes));

        StepVerifier.create(templateService.generatePdfFromSlug("my-resume", null))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get most downloaded templates")
    void shouldGetMostDownloadedTemplates() {
        when(templateRepository.findMostDownloaded(10)).thenReturn(Flux.just(sampleTemplate));
        when(userRepository.findById(1L)).thenReturn(Mono.just(sampleUser));

        StepVerifier.create(templateService.getMostDownloaded(10).collectList())
                .assertNext(list -> {
                    assertThat(list).hasSize(1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get default template")
    void shouldGetDefaultTemplate() {
        sampleTemplate.setIsDefault(true);
        
        when(templateRepository.findByOwnerIdAndIsDefaultTrue(1L))
                .thenReturn(Mono.just(sampleTemplate));
        when(userRepository.findById(1L)).thenReturn(Mono.just(sampleUser));

        StepVerifier.create(templateService.getDefaultTemplate(1L))
                .assertNext(response -> {
                    assertThat(response.getIsDefault()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should set new default and reset previous")
    void shouldSetNewDefaultAndResetPrevious() {
        ResumeTemplateRequest request = ResumeTemplateRequest.builder()
                .name("New Default Template")
                .htmlContent("<html><body>Content</body></html>")
                .isDefault(true)
                .build();

        when(idService.nextId()).thenReturn(300L);
        when(pdfGenerationService.validateHtml(anyString())).thenReturn(Mono.just(true));
        when(templateRepository.existsBySlug(anyString())).thenReturn(Mono.just(false));
        when(templateRepository.resetDefaultForOwner(1L)).thenReturn(Mono.empty());
        when(templateRepository.save(any(ResumeTemplate.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(userRepository.findById(1L)).thenReturn(Mono.just(sampleUser));

        StepVerifier.create(templateService.createTemplate(1L, request))
                .assertNext(response -> {
                    assertThat(response.getIsDefault()).isTrue();
                })
                .verifyComplete();

        verify(templateRepository).resetDefaultForOwner(1L);
    }
}
