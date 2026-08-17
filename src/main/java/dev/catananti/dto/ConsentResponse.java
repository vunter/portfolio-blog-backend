package dev.catananti.dto;

/**
 * The two purpose-specific consents (LGPD art. 8 §4), presented together but
 * stored apart: site navigation on {@code users}, email open/click tracking on
 * {@code subscribers}. {@code null} = never decided — distinct from FALSE
 * (refused), because a refusal must not be re-asked.
 */
public record ConsentResponse(
        Boolean siteAnalyticsConsent,
        Boolean emailAnalyticsConsent) {
}
