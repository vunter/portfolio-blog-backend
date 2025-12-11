package dev.catananti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsSummary {
    private long totalViews;
    private long totalLikes;
    private long totalShares;
    private long uniqueVisitors;
    private List<DailyStat> dailyViews;
    private List<TopArticle> topArticles;
    private List<TopReferrer> topReferrers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyStat {
        private LocalDate date;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopArticle {
        private String articleId;
        private String title;
        private String slug;
        private long views;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopReferrer {
        private String referrer;
        private long count;
    }
}
