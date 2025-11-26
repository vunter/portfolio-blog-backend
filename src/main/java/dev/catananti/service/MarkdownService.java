package dev.catananti.service;

import dev.catananti.util.HtmlUtils;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Service for rendering Markdown content to HTML.
 * Supports GFM extensions: tables, strikethrough, autolinks, task lists.
 */
@Service
@Slf4j
public class MarkdownService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern MD_HEADERS = Pattern.compile("(?s).*#+ .*");
    private static final Pattern MD_BOLD = Pattern.compile("(?s).*\\*\\*.*\\*\\*.*");
    private static final Pattern MD_LINKS = Pattern.compile("(?s).*\\[.*]\\(.*\\).*");
    private static final Pattern MD_CODE_BLOCKS = Pattern.compile("(?s).*```.*```.*");
    private static final Pattern MD_LISTS = Pattern.compile("(?s).*^[-*+] .*");

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final TextContentRenderer textRenderer;

    public MarkdownService() {
        List<Extension> extensions = List.of(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
                TaskListItemsExtension.create()
        );

        this.parser = Parser.builder()
                .extensions(extensions)
                .build();

        this.renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .softbreak("<br />")
                .build();

        // MIN-01: Use CommonMark's TextContentRenderer for reliable plain text extraction
        this.textRenderer = TextContentRenderer.builder()
                .extensions(extensions)
                .build();
    }

    /**
     * Convert Markdown content to HTML.
     *
     * @param markdown The Markdown content to convert
     * @return The rendered HTML
     */
    public String renderToHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        try {
            Node document = parser.parse(markdown);
            return renderer.render(document);
        } catch (Exception e) {
            log.error("Error rendering markdown: {}", e.getMessage());
            return escapeHtml(markdown);
        }
    }

    /**
     * Extract plain text from Markdown (useful for excerpts/previews).
     * MIN-01: Uses CommonMark TextContentRenderer instead of fragile regex chain.
     *
     * @param markdown The Markdown content
     * @param maxLength Maximum length of the plain text
     * @return Plain text extracted from Markdown
     */
    public String extractPlainText(String markdown, int maxLength) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        try {
            Node document = parser.parse(markdown);
            String plainText = textRenderer.render(document).trim();
            plainText = WHITESPACE.matcher(plainText).replaceAll(" ");

            if (plainText.length() > maxLength) {
                return plainText.substring(0, maxLength - 3) + "...";
            }
            return plainText;
        } catch (Exception e) {
            log.warn("Failed to extract plain text from markdown: {}", e.getMessage());
            String stripped = HTML_TAGS.matcher(markdown).replaceAll("");
            stripped = WHITESPACE.matcher(stripped).replaceAll(" ").trim();
            if (stripped.length() > maxLength) {
                return stripped.substring(0, maxLength - 3) + "...";
            }
            return stripped;
        }
    }

    /**
     * Check if content appears to be Markdown.
     *
     * @param content The content to check
     * @return true if the content contains Markdown syntax
     */
    public boolean isMarkdown(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }

        // Check for common Markdown patterns
        return MD_HEADERS.matcher(content).matches() ||
               MD_BOLD.matcher(content).matches() ||
               MD_LINKS.matcher(content).matches() ||
               MD_CODE_BLOCKS.matcher(content).matches() ||
               MD_LISTS.matcher(content).matches();
    }

    /**
     * Escape HTML special characters.
     */
    private String escapeHtml(String text) {
        return HtmlUtils.escapeHtml(text);
    }
}
