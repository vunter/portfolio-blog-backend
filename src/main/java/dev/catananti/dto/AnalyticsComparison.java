package dev.catananti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsComparison {
    private long currentViews;
    private long currentLikes;
    private long currentShares;
    private long previousViews;
    private long previousLikes;
    private long previousShares;
}
