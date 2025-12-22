package dev.catananti.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating or updating a resume template.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeTemplateRequest {

    @NotBlank(message = "Template name is required")
    @Size(min = 3, max = 100, message = "Name must be between 3 and 100 characters")
    private String name;

    @Size(max = 255, message = "Alias cannot exceed 255 characters")
    private String alias;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;

    @NotBlank(message = "HTML content is required")
    @Size(max = 500_000, message = "HTML content too large")
    private String htmlContent;

    @Size(max = 500_000, message = "CSS content too large")
    private String cssContent;

    @Pattern(regexp = "^(DRAFT|ACTIVE|ARCHIVED)$", message = "Status must be DRAFT, ACTIVE, or ARCHIVED")
    private String status;

    @Pattern(regexp = "^(A4|LETTER|LEGAL)$", message = "Paper size must be A4, LETTER, or LEGAL")
    private String paperSize;

    @Pattern(regexp = "^(PORTRAIT|LANDSCAPE)$", message = "Orientation must be PORTRAIT or LANDSCAPE")
    private String orientation;

    private Boolean isDefault;
}
