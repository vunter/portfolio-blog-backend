package dev.catananti.dto;

import java.time.LocalDateTime;

/**
 * Newsletter subscription as seen from the account area.
 *
 * <p>{@code emailAnalyticsConsent} is nullable on purpose: {@code null} means
 * there is no subscriber to hold the consent, which is different from a
 * refusal.
 */
public record AccountNewsletterResponse(
        boolean subscribed,
        boolean linked,
        String subscriberStatus,
        LocalDateTime linkedAt,
        Boolean emailAnalyticsConsent) {
}
