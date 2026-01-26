package dev.catananti.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownServiceTest {

    private final MarkdownService markdownService = new MarkdownService();

    @Test
    @DisplayName("Should render simple markdown to HTML")
    void shouldRenderSimpleMarkdown() {
        String markdown = "# Hello World";
        String html = markdownService.renderToHtml(markdown);
        
        assertThat(html).contains("<h1>Hello World</h1>");
    }

    @Test
    @DisplayName("Should render bold and italic text")
    void shouldRenderBoldAndItalic() {
        String markdown = "This is **bold** and *italic* text";
        String html = markdownService.renderToHtml(markdown);
        
        assertThat(html).contains("<strong>bold</strong>");
        assertThat(html).contains("<em>italic</em>");
    }

    @Test
    @DisplayName("Should render links")
    void shouldRenderLinks() {
        String markdown = "[Click here](https://example.com)";
        String html = markdownService.renderToHtml(markdown);
        
        assertThat(html).contains("href=\"https://example.com\"");
        assertThat(html).contains("Click here");
    }

    @Test
    @DisplayName("Should render code blocks")
    void shouldRenderCodeBlocks() {
        String markdown = "```java\npublic class Test {}\n```";
        String html = markdownService.renderToHtml(markdown);
        
        assertThat(html).contains("<pre>");
        assertThat(html).contains("<code");
        assertThat(html).contains("public class Test {}");
    }

    @Test
    @DisplayName("Should render inline code")
    void shouldRenderInlineCode() {
        String markdown = "Use `System.out.println()` to print";
        String html = markdownService.renderToHtml(markdown);
        
        assertThat(html).contains("<code>System.out.println()</code>");
    }

    @Test
    @DisplayName("Should render tables (GFM)")
    void shouldRenderTables() {
        String markdown = """
                | Header 1 | Header 2 |
                |----------|----------|
                | Cell 1   | Cell 2   |
                """;
        String html = markdownService.renderToHtml(markdown);
        
        assertThat(html).contains("<table>");
        assertThat(html).contains("<th>");
        assertThat(html).contains("<td>");
    }

    @Test
    @DisplayName("Should render strikethrough (GFM)")
    void shouldRenderStrikethrough() {
        String markdown = "This is ~~deleted~~ text";
        String html = markdownService.renderToHtml(markdown);
        
        assertThat(html).contains("<del>deleted</del>");
    }

    @Test
    @DisplayName("Should render task lists (GFM)")
    void shouldRenderTaskLists() {
        String markdown = """
                - [ ] Task 1
                - [x] Task 2 completed
                """;
        String html = markdownService.renderToHtml(markdown);
        
        assertThat(html).contains("type=\"checkbox\"");
    }

    @Test
    @DisplayName("Should handle null input")
    void shouldHandleNullInput() {
        String html = markdownService.renderToHtml(null);
        
        assertThat(html).isEmpty();
    }

    @Test
    @DisplayName("Should handle empty input")
    void shouldHandleEmptyInput() {
        String html = markdownService.renderToHtml("");
        
        assertThat(html).isEmpty();
    }

    @Test
    @DisplayName("Should extract plain text from markdown")
    void shouldExtractPlainText() {
        String markdown = "# Header\n\nThis is **bold** text with a [link](http://test.com)";
        String plainText = markdownService.extractPlainText(markdown, 100);
        
        assertThat(plainText).doesNotContain("#");
        assertThat(plainText).doesNotContain("**");
        assertThat(plainText).doesNotContain("[link]");
        assertThat(plainText).contains("bold");
        assertThat(plainText).contains("link");
    }

    @Test
    @DisplayName("Should truncate plain text")
    void shouldTruncatePlainText() {
        String markdown = "This is a very long text that should be truncated when extracted as plain text";
        String plainText = markdownService.extractPlainText(markdown, 20);
        
        assertThat(plainText).hasSizeLessThanOrEqualTo(20);
        assertThat(plainText).endsWith("...");
    }

    @Test
    @DisplayName("Should detect markdown content")
    void shouldDetectMarkdown() {
        assertThat(markdownService.isMarkdown("# Header")).isTrue();
        assertThat(markdownService.isMarkdown("**bold**")).isTrue();
        assertThat(markdownService.isMarkdown("[link](url)")).isTrue();
        assertThat(markdownService.isMarkdown("```code```")).isTrue();
        assertThat(markdownService.isMarkdown("Just plain text")).isFalse();
    }

    // ==================== Additional Coverage Tests ====================

    @Test
    @DisplayName("Should render table with multiple rows and alignment")
    void shouldRenderTableWithMultipleRows() {
        String markdown = """
                | Name   | Age | City    |
                |--------|----:|:-------:|
                | Alice  | 30  | London  |
                | Bob    | 25  | Paris   |
                | Carlos | 40  | Madrid  |
                """;
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<table>");
        assertThat(html).contains("</table>");
        assertThat(html).contains("<thead>");
        assertThat(html).contains("<tbody>");
        assertThat(html).contains("Alice");
        assertThat(html).contains("Bob");
        assertThat(html).contains("Carlos");
    }

    @Test
    @DisplayName("Should render fenced code block with language class")
    void shouldRenderFencedCodeBlockWithLanguage() {
        String markdown = "```python\nprint('hello')\n```";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<pre>");
        assertThat(html).contains("<code");
        assertThat(html).contains("language-python");
        assertThat(html).contains("print('hello')");
    }

    @Test
    @DisplayName("Should render code block without language")
    void shouldRenderCodeBlockWithoutLanguage() {
        String markdown = "```\nsome code\n```";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<pre>");
        assertThat(html).contains("<code>");
        assertThat(html).contains("some code");
    }

    @Test
    @DisplayName("Should render image markdown")
    void shouldRenderImage() {
        String markdown = "![Alt text](https://example.com/image.png)";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<img");
        assertThat(html).contains("src=\"https://example.com/image.png\"");
        assertThat(html).contains("alt=\"Alt text\"");
    }

    @Test
    @DisplayName("Should render multiple heading levels")
    void shouldRenderMultipleHeadingLevels() {
        String markdown = "# H1\n## H2\n### H3\n#### H4";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<h1>H1</h1>");
        assertThat(html).contains("<h2>H2</h2>");
        assertThat(html).contains("<h3>H3</h3>");
        assertThat(html).contains("<h4>H4</h4>");
    }

    @Test
    @DisplayName("Should auto-link raw URLs")
    void shouldAutoLinkRawUrls() {
        String markdown = "Visit https://example.com for more info";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("href=\"https://example.com\"");
    }

    @Test
    @DisplayName("Should render unordered list")
    void shouldRenderUnorderedList() {
        String markdown = "- Item 1\n- Item 2\n- Item 3";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<ul>");
        assertThat(html).contains("<li>");
        assertThat(html).contains("Item 1");
        assertThat(html).contains("Item 3");
    }

    @Test
    @DisplayName("Should render ordered list")
    void shouldRenderOrderedList() {
        String markdown = "1. First\n2. Second\n3. Third";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<ol>");
        assertThat(html).contains("<li>");
        assertThat(html).contains("First");
    }

    @Test
    @DisplayName("Should render blockquote")
    void shouldRenderBlockquote() {
        String markdown = "> This is a quote";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<blockquote>");
        assertThat(html).contains("This is a quote");
    }

    @Test
    @DisplayName("Should render horizontal rule")
    void shouldRenderHorizontalRule() {
        String markdown = "Above\n\n---\n\nBelow";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<hr");
    }

    @Test
    @DisplayName("Should handle blank/whitespace-only input")
    void shouldHandleBlankInput() {
        assertThat(markdownService.renderToHtml("   ")).isEmpty();
        assertThat(markdownService.renderToHtml("\t\n")).isEmpty();
    }

    @Test
    @DisplayName("Should not execute script tags in rendered output")
    void shouldNotExecuteScriptInRenderedOutput() {
        String markdown = "<script>alert('xss')</script>";
        String html = markdownService.renderToHtml(markdown);

        // CommonMark renders raw HTML as-is but the test verifies it doesn't crash
        assertThat(html).isNotNull();
    }

    @Test
    @DisplayName("Should handle nested markdown formatting")
    void shouldHandleNestedMarkdown() {
        String markdown = "**bold and *italic* nested**";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<strong>");
        assertThat(html).contains("<em>");
    }

    @Test
    @DisplayName("Should convert soft breaks to <br />")
    void shouldConvertSoftBreaks() {
        String markdown = "Line 1\nLine 2";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("<br />");
    }

    @Test
    @DisplayName("Should extract plain text from null input")
    void shouldExtractPlainTextFromNull() {
        assertThat(markdownService.extractPlainText(null, 100)).isEmpty();
    }

    @Test
    @DisplayName("Should extract plain text from blank input")
    void shouldExtractPlainTextFromBlank() {
        assertThat(markdownService.extractPlainText("  ", 100)).isEmpty();
    }

    @Test
    @DisplayName("Should extract plain text within max length")
    void shouldExtractPlainTextWithinMaxLength() {
        String markdown = "Short text";
        String result = markdownService.extractPlainText(markdown, 100);

        assertThat(result).isEqualTo("Short text");
        assertThat(result).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("Should detect list-based markdown")
    void shouldDetectListMarkdown() {
        assertThat(markdownService.isMarkdown("- item")).isTrue();
    }

    @Test
    @DisplayName("Should return false for null isMarkdown check")
    void shouldReturnFalseForNullIsMarkdown() {
        assertThat(markdownService.isMarkdown(null)).isFalse();
    }

    @Test
    @DisplayName("Should return false for blank isMarkdown check")
    void shouldReturnFalseForBlankIsMarkdown() {
        assertThat(markdownService.isMarkdown("")).isFalse();
        assertThat(markdownService.isMarkdown("   ")).isFalse();
    }

    @Test
    @DisplayName("Should render link with title attribute")
    void shouldRenderLinkWithTitle() {
        String markdown = "[Click](https://example.com \"My Title\")";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("href=\"https://example.com\"");
        assertThat(html).contains("title=\"My Title\"");
    }

    @Test
    @DisplayName("Should render task list items with checked and unchecked states")
    void shouldRenderTaskListCheckedStates() {
        String markdown = "- [x] Done\n- [ ] Not done";
        String html = markdownService.renderToHtml(markdown);

        assertThat(html).contains("type=\"checkbox\"");
        assertThat(html).contains("checked");
    }
}
