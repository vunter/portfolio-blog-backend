package dev.catananti.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("XmlUtil")
class XmlUtilTest {

    @Nested
    @DisplayName("escapeXml")
    class EscapeXml {

        @Test
        @DisplayName("should return empty string for null input")
        void shouldReturnEmptyForNull() {
            assertThat(XmlUtil.escapeXml(null)).isEmpty();
        }

        @Test
        @DisplayName("should return empty string for empty input")
        void shouldReturnEmptyForEmpty() {
            assertThat(XmlUtil.escapeXml("")).isEmpty();
        }

        @Test
        @DisplayName("should leave plain text unchanged")
        void shouldLeavePlainTextUnchanged() {
            assertThat(XmlUtil.escapeXml("Hello World")).isEqualTo("Hello World");
        }

        @Test
        @DisplayName("should escape ampersand")
        void shouldEscapeAmpersand() {
            assertThat(XmlUtil.escapeXml("Tom & Jerry")).isEqualTo("Tom &amp; Jerry");
        }

        @Test
        @DisplayName("should escape less-than")
        void shouldEscapeLessThan() {
            assertThat(XmlUtil.escapeXml("a < b")).isEqualTo("a &lt; b");
        }

        @Test
        @DisplayName("should escape greater-than")
        void shouldEscapeGreaterThan() {
            assertThat(XmlUtil.escapeXml("a > b")).isEqualTo("a &gt; b");
        }

        @Test
        @DisplayName("should escape double quote")
        void shouldEscapeDoubleQuote() {
            assertThat(XmlUtil.escapeXml("say \"hello\"")).isEqualTo("say &quot;hello&quot;");
        }

        @Test
        @DisplayName("should escape single quote")
        void shouldEscapeSingleQuote() {
            assertThat(XmlUtil.escapeXml("it's")).isEqualTo("it&apos;s");
        }

        @Test
        @DisplayName("should escape mixed content with all special characters")
        void shouldEscapeMixedContent() {
            String input = "<div class=\"test\">'Tom & Jerry'</div>";
            String expected = "&lt;div class=&quot;test&quot;&gt;&apos;Tom &amp; Jerry&apos;&lt;/div&gt;";
            assertThat(XmlUtil.escapeXml(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("should double-encode already-escaped content")
        void shouldDoubleEncodeAlreadyEscaped() {
            String input = "&amp;";
            String expected = "&amp;amp;";
            assertThat(XmlUtil.escapeXml(input)).isEqualTo(expected);
        }
    }
}
