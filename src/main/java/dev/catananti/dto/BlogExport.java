package dev.catananti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Complete blog export containing all articles, tags, and metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlogExport {
    private String version;
    private LocalDateTime exportedAt;
    private String exportedBy;
    private BlogStats stats;
    private List<ArticleExportData> articles;
    private List<TagExportData> tags;
    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlogStats {
        private long totalArticles;
        private long publishedArticles;
        private long draftArticles;
        private long scheduledArticles;
        private long totalTags;
        private long totalViews;
        private long totalLikes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TagExportData {
        private String name;
        private String slug;
        private String description;
        private String color;
    }
}
