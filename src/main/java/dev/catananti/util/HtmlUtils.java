package dev.catananti.util;

/**
 * M-1: Shared HTML utility methods — single source of truth.
 * Replaces private escapeHtml() copies in EmailService, MarkdownService,
 * PdfGenerationService, and ResumeProfileService.
 */
public final class HtmlUtils {

    private HtmlUtils() {}

    /**
     * Escape HTML special characters to prevent XSS.
     * Escapes: &amp; &lt; &gt; &quot; &#x27;
     */
    public static String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
