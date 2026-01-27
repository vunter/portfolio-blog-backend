package dev.catananti.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PdfGenerationService Tests")
class PdfGenerationServiceTest {

    private final PdfGenerationService pdfService = new PdfGenerationService();

    @Test
    @DisplayName("Should generate PDF from simple HTML")
    void shouldGeneratePdfFromSimpleHtml() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body><h1>Hello World</h1><p>This is a test.</p></body>
            </html>
            """;

        StepVerifier.create(pdfService.generatePdf(html, "A4", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    assertThat(bytes.length).isGreaterThan(0);
                    // PDF magic number: %PDF
                    assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should generate PDF with landscape orientation")
    void shouldGeneratePdfWithLandscape() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Landscape Test</title></head>
            <body><h1>Landscape Document</h1></body>
            </html>
            """;

        StepVerifier.create(pdfService.generatePdf(html, "A4", true))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    assertThat(bytes.length).isGreaterThan(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should generate PDF with Letter paper size")
    void shouldGeneratePdfWithLetterSize() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Letter Size</title></head>
            <body><h1>Letter Size Document</h1></body>
            </html>
            """;

        StepVerifier.create(pdfService.generatePdf(html, "LETTER", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should substitute variables in template")
    void shouldSubstituteVariables() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Template</title></head>
            <body>
                <h1>{{name}}</h1>
                <p>Email: ${email}</p>
                <p>Role: {{role}}</p>
            </body>
            </html>
            """;

        Map<String, String> variables = Map.of(
                "name", "John Doe",
                "email", "john@example.com",
                "role", "Software Engineer"
        );

        StepVerifier.create(pdfService.generatePdfWithVariables(html, variables, "A4", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    assertThat(bytes.length).isGreaterThan(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle null variables map")
    void shouldHandleNullVariables() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body><h1>No Variables</h1></body>
            </html>
            """;

        StepVerifier.create(pdfService.generatePdfWithVariables(html, null, "A4", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate valid HTML")
    void shouldValidateValidHtml() {
        String validHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Valid</title></head>
            <body><p>Content</p></body>
            </html>
            """;

        StepVerifier.create(pdfService.validateHtml(validHtml))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate incomplete HTML")
    void shouldValidateIncompleteHtml() {
        // JSoup is lenient and will create proper structure
        String partialHtml = "<p>Just a paragraph</p>";

        StepVerifier.create(pdfService.validateHtml(partialHtml))
                .expectNext(true) // JSoup auto-corrects structure
                .verifyComplete();
    }

    @Test
    @DisplayName("Should extract text preview from HTML")
    void shouldExtractTextPreview() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body>
                <h1>Title</h1>
                <p>This is the content of the document.</p>
            </body>
            </html>
            """;

        String preview = pdfService.extractTextPreview(html, 50);

        assertThat(preview).isNotNull();
        assertThat(preview).contains("Title");
        assertThat(preview).contains("content");
    }

    @Test
    @DisplayName("Should truncate long text preview")
    void shouldTruncateLongTextPreview() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body>
                <p>This is a very long paragraph that contains more than twenty characters and should be truncated.</p>
            </body>
            </html>
            """;

        String preview = pdfService.extractTextPreview(html, 20);

        assertThat(preview).hasSize(23); // 20 + "..."
        assertThat(preview).endsWith("...");
    }

    @Test
    @DisplayName("Should generate PDF with CSS styles")
    void shouldGeneratePdfWithCssStyles() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Styled Document</title>
                <style>
                    body { font-family: Arial, sans-serif; }
                    h1 { color: #333; font-size: 24pt; }
                    .highlight { background-color: yellow; }
                    @page { margin: 1in; }
                </style>
            </head>
            <body>
                <h1>Styled Document</h1>
                <p class="highlight">This text is highlighted.</p>
            </body>
            </html>
            """;

        StepVerifier.create(pdfService.generatePdf(html, "A4", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    assertThat(bytes.length).isGreaterThan(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle resume-like HTML template")
    void shouldHandleResumeTemplate() {
        String resumeHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>{{name}} - Resume</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: Arial, sans-serif; font-size: 10pt; }
                    .header { text-align: center; margin-bottom: 20px; }
                    .name { font-size: 18pt; font-weight: bold; }
                    .section { margin-bottom: 15px; }
                    .section-title { font-weight: bold; border-bottom: 1px solid #ccc; }
                    @page { size: A4; margin: 15mm; }
                </style>
            </head>
            <body>
                <div class="header">
                    <div class="name">{{name}}</div>
                    <div>{{title}}</div>
                    <div>{{email}} | {{phone}}</div>
                </div>
                <div class="section">
                    <div class="section-title">PROFESSIONAL SUMMARY</div>
                    <p>{{summary}}</p>
                </div>
                <div class="section">
                    <div class="section-title">EXPERIENCE</div>
                    <p>{{experience}}</p>
                </div>
            </body>
            </html>
            """;

        Map<String, String> variables = Map.of(
                "name", "Leonardo Catananti",
                "title", "Senior Software Engineer",
                "email", "leo@example.com",
                "phone", "+1 555-123-4567",
                "summary", "9+ years of experience in backend development with Java and Spring Boot.",
                "experience", "Senior Java Developer at Indeed since 2024."
        );

        StepVerifier.create(pdfService.generatePdfWithVariables(resumeHtml, variables, "A4", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    assertThat(bytes.length).isGreaterThan(0);
                    // Verify it's a valid PDF
                    assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should escape HTML special characters in variables")
    void shouldEscapeHtmlInVariables() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body><p>Value: {{value}}</p></body>
            </html>
            """;

        // Variable with HTML special characters
        Map<String, String> variables = Map.of(
                "value", "<script>alert('xss')</script> & \"quotes\""
        );

        StepVerifier.create(pdfService.generatePdfWithVariables(html, variables, "A4", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    // Should not crash, HTML should be escaped
                })
                .verifyComplete();
    }

    // ==================== Additional Coverage Tests ====================

    @Test
    @DisplayName("Should validate null HTML as invalid")
    void shouldValidateNullHtmlAsInvalid() {
        StepVerifier.create(pdfService.validateHtml(null))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate empty HTML as invalid")
    void shouldValidateEmptyHtmlAsInvalid() {
        StepVerifier.create(pdfService.validateHtml(""))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate blank HTML as invalid")
    void shouldValidateBlankHtmlAsInvalid() {
        StepVerifier.create(pdfService.validateHtml("   "))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate plain text without tags as invalid")
    void shouldValidatePlainTextAsInvalid() {
        StepVerifier.create(pdfService.validateHtml("Just some plain text"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate DOCTYPE HTML as valid")
    void shouldValidateDoctypeAsValid() {
        StepVerifier.create(pdfService.validateHtml("<!DOCTYPE html><html><body></body></html>"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate HTML with closing tags as valid")
    void shouldValidateClosingTagsAsValid() {
        StepVerifier.create(pdfService.validateHtml("<div>content</div>"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle empty variables map")
    void shouldHandleEmptyVariables() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body><h1>No Variables</h1></body>
            </html>
            """;

        StepVerifier.create(pdfService.generatePdfWithVariables(html, Map.of(), "A4", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    assertThat(bytes.length).isGreaterThan(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should extract empty text preview for empty HTML")
    void shouldExtractEmptyTextPreviewForEmptyHtml() {
        String preview = pdfService.extractTextPreview("<html><body></body></html>", 100);
        assertThat(preview).isNotNull();
    }

    @Test
    @DisplayName("Should extract text preview stripping all HTML tags")
    void shouldExtractTextStrippingTags() {
        String html = "<html><body><h1>Title</h1><p>Paragraph <strong>bold</strong></p></body></html>";
        String preview = pdfService.extractTextPreview(html, 100);

        assertThat(preview).contains("Title");
        assertThat(preview).contains("Paragraph");
        assertThat(preview).contains("bold");
        assertThat(preview).doesNotContain("<h1>");
        assertThat(preview).doesNotContain("<strong>");
    }

    @Test
    @DisplayName("Should handle variables with null values")
    void shouldHandleVariablesWithNullValues() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body><p>{{name}}</p></body>
            </html>
            """;

        Map<String, String> variables = new java.util.HashMap<>();
        variables.put("name", null);

        StepVerifier.create(pdfService.generatePdfWithVariables(html, variables, "A4", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    assertThat(bytes.length).isGreaterThan(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("PaperSize enum should have correct format values")
    void shouldHaveCorrectPaperSizeFormats() {
        assertThat(PdfGenerationService.PaperSize.A4.getFormat()).isEqualTo("A4");
        assertThat(PdfGenerationService.PaperSize.LETTER.getFormat()).isEqualTo("Letter");
        assertThat(PdfGenerationService.PaperSize.LEGAL.getFormat()).isEqualTo("Legal");
    }

    @Test
    @DisplayName("PaperSize enum should have exactly 3 values")
    void shouldHaveThreePaperSizes() {
        assertThat(PdfGenerationService.PaperSize.values()).hasSize(3);
    }

    @Test
    @DisplayName("Should generate PDF with Legal paper size")
    void shouldGeneratePdfWithLegalSize() {
        String html = """
            <!DOCTYPE html>
            <html>
            <head><title>Legal Size</title></head>
            <body><h1>Legal Size Document</h1></body>
            </html>
            """;

        StepVerifier.create(pdfService.generatePdf(html, "LEGAL", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    assertThat(bytes.length).isGreaterThan(0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should generate PDF with multi-page content")
    void shouldGeneratePdfWithMultiPageContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><title>Long Doc</title></head><body>");
        for (int i = 0; i < 100; i++) {
            sb.append("<p>Paragraph ").append(i).append(" with some content to fill the page.</p>");
        }
        sb.append("</body></html>");

        StepVerifier.create(pdfService.generatePdf(sb.toString(), "A4", false))
                .assertNext(bytes -> {
                    assertThat(bytes).isNotNull();
                    assertThat(bytes.length).isGreaterThan(0);
                    assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF");
                })
                .verifyComplete();
    }
}
