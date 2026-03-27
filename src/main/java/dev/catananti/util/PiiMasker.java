package dev.catananti.util;

import org.springframework.security.core.Authentication;

/**
 * Utility for masking personally identifiable information (PII) in log output.
 * Q7.12: Prevents email addresses from appearing in plain text in logs.
 * Q7.15: Provides safe extraction of email from Authentication principal.
 */
public final class PiiMasker {
    private PiiMasker() {}

    /**
     * Mask an email address for safe logging: first 3 chars + "***" + "@" + domain.
     * Example: "john.doe@gmail.com" -> "joh***@gmail.com"
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "***";
        int atIdx = email.indexOf('@');
        if (atIdx <= 0) return "***";
        String localPart = email.substring(0, Math.min(3, atIdx));
        String domain = email.substring(atIdx);
        return localPart + "***" + domain;
    }

    /**
     * Q7.15: Extract email from Authentication principal with validation.
     * Throws IllegalStateException if the principal is not an email address.
     */
    public static String extractEmail(Authentication authentication) {
        String name = authentication.getName();
        if (name == null || !name.contains("@")) {
            throw new IllegalStateException("Authentication principal is not an email address: " + maskEmail(name));
        }
        return name;
    }
}
