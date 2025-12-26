package dev.catananti.dto;

import dev.catananti.entity.ArticleVersion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleVersionResponse {
    private String id;
    private String articleId;
    private Integer versionNumber;
    private String title;
    private String subtitle;
    private String content;
    private String excerpt;
    private String coverImageUrl;
    private String changeSummary;
    private String changedByName;
    private LocalDateTime createdAt;

    public static ArticleVersionResponse fromEntity(ArticleVersion version) {
        return ArticleVersionResponse.builder()
                .id(String.valueOf(version.getId()))
                .articleId(String.valueOf(version.getArticleId()))
                .versionNumber(version.getVersionNumber())
                .title(version.getTitle())
                .subtitle(version.getSubtitle())
                .content(version.getContent())
                .excerpt(version.getExcerpt())
                .coverImageUrl(version.getCoverImageUrl())
                .changeSummary(version.getChangeSummary())
                .changedByName(version.getChangedByName())
                .createdAt(version.getCreatedAt())
                .build();
    }
}
