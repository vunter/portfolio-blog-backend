package dev.catananti.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;

    @Size(max = 100, message = "Slug must not exceed 100 characters")
    @Pattern(regexp = "^[a-z0-9-]*$", message = "Slug must contain only lowercase letters, numbers and hyphens")
    private String slug;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must be a valid hex color (e.g., #ffffff)")
    private String color;
}
