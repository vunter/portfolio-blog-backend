package dev.catananti.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request DTO for PDF generation from template.
 * TODO F-282: Consider converting to a record class for immutability (Java 16+)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfGenerationRequest {

    /**
     * Template ID to use for PDF generation.
     * If not provided, uses raw HTML content.
     */
    private Long templateId;

    /**
     * Template slug to use (alternative to templateId).
     */
    private String templateSlug;

    /**
     * Raw HTML content for direct PDF conversion.
     * Used when templateId is not provided.
     */
    // TODO F-273: Validate URL against allowlist to prevent SSRF
    @Size(max = 500_000, message = "HTML content too large")
    private String htmlContent;

    /**
     * Variables to replace in the template (placeholder substitution).
     * Keys are placeholder names, values are replacement text.
     * Example: {"name": "John Doe", "email": "john@example.com"}
     */
    private Map<String, String> variables;

    /**
     * Paper size override.
     */
    @Pattern(regexp = "^(A4|LETTER|LEGAL)$", message = "Paper size must be A4, LETTER, or LEGAL")
    private String paperSize;

    /**
     * Orientation override.
     */
    @Pattern(regexp = "^(PORTRAIT|LANDSCAPE)$", message = "Orientation must be PORTRAIT or LANDSCAPE")
    private String orientation;

    /**
     * Custom filename for the generated PDF.
     */
    private String filename;
}
