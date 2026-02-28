package dev.catananti.service;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Service for sanitizing HTML content to prevent XSS attacks.
 * Uses JSoup with a whitelist approach for reliable HTML sanitization.
 */
@Slf4j
@Service
public class HtmlSanitizerService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Whitelist for rich content (articles, comments with formatting).
     * Allows safe structural and formatting tags but blocks scripts, iframes, forms, etc.
     * F-037: Explicitly remove 'style' attribute to prevent CSS-based data exfiltration.
     */
    private static final Safelist RICH_CONTENT_SAFELIST = Safelist.relaxed()
            .removeTags("form", "input", "textarea", "select", "button")
            .removeAttributes("a", "onclick", "onmouseover", "onfocus")
            .removeAttributes(":all", "style")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https");

    /**
     * Safelist for resume content fields — allows basic inline formatting and links only.
     * No images, tables, block elements, or other complex HTML.
     */
    private static final Safelist RESUME_CONTENT_SAFELIST = new Safelist()
            .addTags("a", "strong", "b", "em", "i", "br", "span", "u")
            .addAttributes("a", "href", "class", "target", "rel")
            .addAttributes("span", "class")
            .addProtocols("a", "href", "http", "https", "mailto");

    /**
     * Sanitize user-provided HTML content by removing dangerous elements and attributes.
     * Allows safe formatting tags (p, b, i, a, img, ul, ol, etc.) while removing
     * scripts, iframes, event handlers, and other XSS vectors.
     *
     * @param input The raw user input
     * @return Sanitized content safe for storage and rendering
     */
    public String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        log.debug("Sanitizing rich HTML content, length={}", input.length());
        return Jsoup.clean(input, RICH_CONTENT_SAFELIST);
    }

    /**
     * Sanitize HTML for resume content fields.
     * Allows basic formatting (bold, italic, underline) and links while stripping
     * everything else (images, scripts, tables, etc.).
     *
     * @param input The raw user input (may contain HTML)
     * @return Sanitized HTML safe for resume rendering
     */
    public String sanitizeResumeContent(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        log.debug("Sanitizing resume HTML content, length={}", input.length());
        return Jsoup.clean(input, RESUME_CONTENT_SAFELIST);
    }

    /**
     * Sanitize plain text content by escaping HTML entities.
     * Use this for content that should not contain any HTML.
     *
     * @param input The raw user input
     * @return Content with HTML entities escaped
     */
    public String escapeHtml(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    /**
     * Sanitize content for use in plain text contexts (comments, names, etc.).
     * Strips all HTML and normalizes whitespace.
     *
     * @param input The raw user input
     * @return Plain text without HTML
     */
    public String stripHtml(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        // JSoup clean with none() safelist strips all tags
        String stripped = Jsoup.clean(input, Safelist.none());
        // Normalize whitespace
        stripped = WHITESPACE.matcher(stripped).replaceAll(" ");
        return stripped.trim();
    }
}
