package dev.catananti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * DTO for article export/import operations.
 * Contains all article data in a portable format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleExportData {
    private String id;
    private String slug;
    private String title;
    private String subtitle;
    private String content;
    private String excerpt;
    private String coverImageUrl;
    private String status;
    private LocalDateTime publishedAt;
    private LocalDateTime scheduledAt;
    private Integer readingTimeMinutes;
    private Integer viewsCount;
    private Integer likesCount;
    private Set<String> tagSlugs;
    private String seoTitle;
    private String seoDescription;
    private String seoKeywords;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
