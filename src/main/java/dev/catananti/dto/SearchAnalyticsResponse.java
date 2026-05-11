package dev.catananti.dto;

import java.util.List;

/**
 * Q2.4: Typed response for search analytics aggregation.
 * Replaces the untyped Map<String, Object> previously returned from AdminAnalyticsController.
 */
public record SearchAnalyticsResponse(
        long totalSearches,
        long uniqueSearches,
        List<SearchQueryStat> topSearches,
        List<SearchQueryStat> zeroResultSearches
) {
    public record SearchQueryStat(String queryText, long count) {}
}
