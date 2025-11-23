package dev.catananti.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

// TODO F-263: Consider a sealed interface ArticleView with ArticleSummary and ArticleDetail implementations
// TODO F-267: Evaluate converting to a record DTO for immutability (requires removing @Builder/@Data)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleResponse {
    private String id;
    private String slug;
    private String title;
    private String subtitle;
    private String content;
    private String contentHtml;  // Rendered HTML from Markdown content
    private String excerpt;
    private String coverImageUrl;
    private AuthorInfo author;
    private String status;
    private LocalDateTime publishedAt;
    private LocalDateTime scheduledAt;
    private Integer readingTimeMinutes;
    private Integer viewCount;
    private Integer likeCount;
    private Integer commentCount;
    private Set<TagResponse> tags;
    private String seoTitle;
    private String seoDescription;
    private String seoKeywords;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthorInfo {
        private String id;
        private String name;
        private String avatarUrl;
        private String bio;
    }
}
