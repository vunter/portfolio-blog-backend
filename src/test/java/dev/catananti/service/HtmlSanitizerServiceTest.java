package dev.catananti.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlSanitizerServiceTest {

    private HtmlSanitizerService sanitizerService;

    @BeforeEach
    void setUp() {
        sanitizerService = new HtmlSanitizerService();
    }

    @Nested
    @DisplayName("sanitize - rich content")
    class Sanitize {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertThat(sanitizerService.sanitize(null)).isNull();
        }

        // jsoup 1.20.1 stopped honouring self-closing syntax on non-void tags:
        // <div/> no longer closes itself, so everything after it becomes a child
        // instead of a sibling. That reshapes the tree a PDF is laid out from.
        // Pinning it here means the next jsoup bump has to face the change
        // instead of silently re-nesting stored content.
        @Test
        @DisplayName("Self-closing non-void tags nest their siblings (jsoup >= 1.20.1)")
        void shouldNestSiblingsAfterSelfClosingNonVoidTag() {
            String sanitized = sanitizerService.sanitize("<div/><p>after</p>");

            assertThat(sanitized).contains("<p>after</p>");
            assertThat(sanitized.replaceAll("\\s+", ""))
                    .as("the <p> is parsed inside the <div>, not beside it")
                    .contains("<div><p>after</p></div>");
        }

        // 1.21.1 escapes < and > inside attribute values as an mXSS defence.
        @Test
        @DisplayName("Angle brackets inside attributes are escaped (jsoup >= 1.21.1)")
        void shouldEscapeAngleBracketsInAttributes() {
            String sanitized = sanitizerService.sanitize("<a href=\"/x\" title=\"a<b\">link</a>");

            assertThat(sanitized).contains("&lt;b");
            assertThat(sanitized).doesNotContain("title=\"a<b\"");
        }

        @Test
        @DisplayName("Should return empty string for empty input")
        void shouldReturnEmptyForEmpty() {
            assertThat(sanitizerService.sanitize("")).isEmpty();
        }

        @Test
        @DisplayName("Should preserve safe formatting tags")
        void shouldPreserveSafeTags() {
            String input = "<p>Hello <b>world</b> and <i>italic</i></p>";
            String result = sanitizerService.sanitize(input);
            assertThat(result).contains("<p>", "<b>", "<i>");
            assertThat(result).contains("Hello", "world", "italic");
        }

        @Test
        @DisplayName("Should preserve links with safe protocols")
        void shouldPreserveSafeLinks() {
            String input = "<a href=\"https://example.com\">Link</a>";
            String result = sanitizerService.sanitize(input);
            assertThat(result).contains("href=\"https://example.com\"");
            assertThat(result).contains("Link");
        }

        @Test
        @DisplayName("Should preserve mailto links")
        void shouldPreserveMailtoLinks() {
            String input = "<a href=\"mailto:test@example.com\">Email</a>";
            String result = sanitizerService.sanitize(input);
            assertThat(result).contains("mailto:test@example.com");
        }

        @Test
        @DisplayName("Should remove script tags")
        void shouldRemoveScriptTags() {
            String input = "<p>Safe</p><script>alert('xss')</script>";
            String result = sanitizerService.sanitize(input);
            assertThat(result).doesNotContain("<script>");
            assertThat(result).doesNotContain("alert");
            assertThat(result).contains("Safe");
        }

        @Test
        @DisplayName("Should remove iframe tags")
        void shouldRemoveIframeTags() {
            String input = "<p>Content</p><iframe src=\"evil.com\"></iframe>";
            String result = sanitizerService.sanitize(input);
            assertThat(result).doesNotContain("<iframe>");
            assertThat(result).doesNotContain("evil.com");
        }

        @Test
        @DisplayName("Should remove event handler attributes")
        void shouldRemoveEventHandlers() {
            String input = "<a href=\"#\" onclick=\"alert('xss')\">Click</a>";
            String result = sanitizerService.sanitize(input);
            assertThat(result).doesNotContain("onclick");
            assertThat(result).doesNotContain("alert");
        }

        @Test
        @DisplayName("Should remove form elements")
        void shouldRemoveFormElements() {
            String input = "<form action=\"/steal\"><input type=\"text\"><button>Submit</button></form>";
            String result = sanitizerService.sanitize(input);
            assertThat(result).doesNotContain("<form>");
            assertThat(result).doesNotContain("<input");
            assertThat(result).doesNotContain("<button>");
        }

        @Test
        @DisplayName("Should remove javascript protocol in href")
        void shouldRemoveJavascriptProtocol() {
            String input = "<a href=\"javascript:alert('xss')\">Click</a>";
            String result = sanitizerService.sanitize(input);
            assertThat(result).doesNotContain("javascript:");
        }

        @Test
        @DisplayName("Should preserve images with safe src")
        void shouldPreserveSafeImages() {
            String input = "<img src=\"https://example.com/photo.jpg\" alt=\"Photo\">";
            String result = sanitizerService.sanitize(input);
            assertThat(result).contains("src=\"https://example.com/photo.jpg\"");
        }

        @Test
        @DisplayName("Should preserve lists")
        void shouldPreserveLists() {
            String input = "<ul><li>Item 1</li><li>Item 2</li></ul>";
            String result = sanitizerService.sanitize(input);
            assertThat(result).contains("<ul>", "<li>");
        }
    }

    @Nested
    @DisplayName("escapeHtml - plain text escaping")
    class EscapeHtml {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertThat(sanitizerService.escapeHtml(null)).isNull();
        }

        @Test
        @DisplayName("Should escape HTML special characters")
        void shouldEscapeSpecialCharacters() {
            String input = "<script>alert(\"xss\")</script>";
            String result = sanitizerService.escapeHtml(input);
            assertThat(result).doesNotContain("<script>");
            assertThat(result).contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("Should escape ampersand")
        void shouldEscapeAmpersand() {
            String result = sanitizerService.escapeHtml("A & B");
            assertThat(result).contains("&amp;");
        }

        @Test
        @DisplayName("Should escape quotes")
        void shouldEscapeQuotes() {
            String result = sanitizerService.escapeHtml("He said \"hello\"");
            assertThat(result).contains("&quot;");
        }
    }

    @Nested
    @DisplayName("stripHtml - remove all HTML")
    class StripHtml {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertThat(sanitizerService.stripHtml(null)).isNull();
        }

        @Test
        @DisplayName("Should strip all HTML tags")
        void shouldStripAllTags() {
            String input = "<p>Hello <b>world</b> with <a href=\"#\">links</a></p>";
            String result = sanitizerService.stripHtml(input);
            assertThat(result).isEqualTo("Hello world with links");
        }

        @Test
        @DisplayName("Should normalize whitespace")
        void shouldNormalizeWhitespace() {
            String input = "<p>Hello</p>  <p>World</p>";
            String result = sanitizerService.stripHtml(input);
            assertThat(result).doesNotContain("  ");
        }

        @Test
        @DisplayName("Should strip script content")
        void shouldStripScriptContent() {
            String input = "Hello<script>alert('xss')</script>World";
            String result = sanitizerService.stripHtml(input);
            assertThat(result).doesNotContain("script");
            assertThat(result).doesNotContain("alert");
        }
    }
}
