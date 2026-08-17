package dev.catananti.dto;

/**
 * Partial consent update: an absent field means "do not touch that consent",
 * so each purpose can be decided independently.
 */
public record ConsentUpdateRequest(
        Boolean siteAnalyticsConsent,
        Boolean emailAnalyticsConsent) {
}
