package dev.catananti.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleRequest {

    @NotBlank(message = "Slug is required")
    @Size(min = 3, max = 255, message = "Slug must be between 3 and 255 characters")
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug must be lowercase alphanumeric with hyphens only")
    private String slug;

    @NotBlank(message = "Title is required")
    @Size(min = 3, max = 500, message = "Title must be between 3 and 500 characters")
    private String title;

    @Size(max = 500, message = "Subtitle must be at most 500 characters")
    private String subtitle;

    @NotBlank(message = "Content is required")
    @Size(min = 10, max = 500000, message = "Content must be between 10 and 500000 characters")
    private String content;

    @Size(max = 1000, message = "Excerpt must be at most 1000 characters")
    private String excerpt;

    @Size(max = 500, message = "Cover image URL must be at most 500 characters")
    @Pattern(regexp = "^(https?://.*)?$", message = "Cover image URL must be a valid HTTP(S) URL")
    private String coverImageUrl;

    @Pattern(regexp = "^(DRAFT|PUBLISHED|SCHEDULED|ARCHIVED)?$", message = "Status must be DRAFT, PUBLISHED, SCHEDULED, or ARCHIVED")
    private String status;

    private LocalDateTime scheduledAt;

    @Size(max = 20, message = "Maximum 20 tags allowed")
    private List<String> tagSlugs;

    @Size(max = 255, message = "SEO title must be at most 255 characters")
    private String seoTitle;

    @Size(max = 500, message = "SEO description must be at most 500 characters")
    private String seoDescription;

    @Size(max = 500, message = "SEO keywords must be at most 500 characters")
    private String seoKeywords;
}
