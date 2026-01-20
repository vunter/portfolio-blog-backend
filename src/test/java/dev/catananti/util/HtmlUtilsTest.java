package dev.catananti.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HtmlUtils")
class HtmlUtilsTest {

    @Nested
    @DisplayName("escapeHtml")
    class EscapeHtml {

        @Test
        @DisplayName("should return empty string for null input")
        void shouldReturnEmptyForNull() {
            assertThat(HtmlUtils.escapeHtml(null)).isEmpty();
        }

        @Test
        @DisplayName("should return empty string for empty input")
        void shouldReturnEmptyForEmpty() {
            assertThat(HtmlUtils.escapeHtml("")).isEmpty();
        }

        @Test
        @DisplayName("should leave plain text unchanged")
        void shouldLeavePlainTextUnchanged() {
            assertThat(HtmlUtils.escapeHtml("Hello World 123")).isEqualTo("Hello World 123");
        }

        @Test
        @DisplayName("should escape ampersand")
        void shouldEscapeAmpersand() {
            assertThat(HtmlUtils.escapeHtml("A & B")).isEqualTo("A &amp; B");
        }

        @Test
        @DisplayName("should escape less-than")
        void shouldEscapeLessThan() {
            assertThat(HtmlUtils.escapeHtml("a < b")).isEqualTo("a &lt; b");
        }

        @Test
        @DisplayName("should escape greater-than")
        void shouldEscapeGreaterThan() {
            assertThat(HtmlUtils.escapeHtml("a > b")).isEqualTo("a &gt; b");
        }

        @Test
        @DisplayName("should escape double quote")
        void shouldEscapeDoubleQuote() {
            assertThat(HtmlUtils.escapeHtml("say \"hi\"")).isEqualTo("say &quot;hi&quot;");
        }

        @Test
        @DisplayName("should escape single quote")
        void shouldEscapeSingleQuote() {
            assertThat(HtmlUtils.escapeHtml("it's")).isEqualTo("it&#x27;s");
        }

        @Test
        @DisplayName("should escape XSS vector script tag")
        void shouldEscapeXssVector() {
            String input = "<script>alert('xss')</script>";
            String expected = "&lt;script&gt;alert(&#x27;xss&#x27;)&lt;/script&gt;";
            assertThat(HtmlUtils.escapeHtml(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("should escape mixed HTML content")
        void shouldEscapeMixedHtmlContent() {
            String input = "<a href=\"url\">Tom & Jerry's</a>";
            String expected = "&lt;a href=&quot;url&quot;&gt;Tom &amp; Jerry&#x27;s&lt;/a&gt;";
            assertThat(HtmlUtils.escapeHtml(input)).isEqualTo(expected);
        }
    }
}
