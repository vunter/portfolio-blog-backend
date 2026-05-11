package dev.catananti.config.converter;

import dev.catananti.entity.LocalizedText;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("R2DBC Converters")
class ConverterTest {

    @Nested
    @DisplayName("JsonToLocalizedTextConverter")
    class JsonToLocalizedText {

        private final JsonToLocalizedTextConverter converter = new JsonToLocalizedTextConverter();

        @Test
        @DisplayName("should convert JSON to LocalizedText")
        void shouldConvertJsonToLocalizedText() {
            Json json = Json.of("{\"en\":\"Hello\",\"pt-br\":\"Olá\"}");

            LocalizedText result = converter.convert(json);

            assertThat(result.get("en")).isEqualTo("Hello");
            assertThat(result.get("pt-br")).isEqualTo("Olá");
        }

        @Test
        @DisplayName("should handle empty JSON object")
        void shouldHandleEmptyJson() {
            Json json = Json.of("{}");

            LocalizedText result = converter.convert(json);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("should handle single locale")
        void shouldHandleSingleLocale() {
            Json json = Json.of("{\"en\":\"Only English\"}");

            LocalizedText result = converter.convert(json);

            assertThat(result.get("en")).isEqualTo("Only English");
            assertThat(result.getTranslations()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("LocalizedTextToJsonConverter")
    class LocalizedTextToJson {

        private final LocalizedTextToJsonConverter converter = new LocalizedTextToJsonConverter();

        @Test
        @DisplayName("should convert LocalizedText to JSON")
        void shouldConvertToJson() {
            LocalizedText text = new LocalizedText(Map.of("en", "Hello", "es", "Hola"));

            Json result = converter.convert(text);

            String json = result.asString();
            assertThat(json).contains("\"en\"");
            assertThat(json).contains("\"Hello\"");
            assertThat(json).contains("\"es\"");
            assertThat(json).contains("\"Hola\"");
        }

        @Test
        @DisplayName("should handle empty LocalizedText")
        void shouldHandleEmpty() {
            LocalizedText text = new LocalizedText();

            Json result = converter.convert(text);

            assertThat(result.asString()).isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("LocalizedTextToStringConverter (H2)")
    class LocalizedTextToString {

        private final LocalizedTextToStringConverter converter = new LocalizedTextToStringConverter();

        @Test
        @DisplayName("should convert LocalizedText to JSON string")
        void shouldConvertToString() {
            LocalizedText text = LocalizedText.ofEnglish("Hello");

            String result = converter.convert(text);

            assertThat(result).contains("\"en\"").contains("\"Hello\"");
        }

        @Test
        @DisplayName("should produce valid JSON")
        void shouldProduceValidJson() {
            LocalizedText text = new LocalizedText(Map.of("en", "Hello", "pt-br", "Olá"));

            String result = converter.convert(text);

            assertThat(result).startsWith("{").endsWith("}");
        }
    }

    @Nested
    @DisplayName("StringToLocalizedTextConverter (H2)")
    class StringToLocalizedText {

        private final StringToLocalizedTextConverter converter = new StringToLocalizedTextConverter();

        @Test
        @DisplayName("should convert JSON string to LocalizedText")
        void shouldConvertFromString() {
            LocalizedText result = converter.convert("{\"en\":\"Hello\",\"fr\":\"Bonjour\"}");

            assertThat(result.get("en")).isEqualTo("Hello");
            assertThat(result.get("fr")).isEqualTo("Bonjour");
        }

        @Test
        @DisplayName("should handle plain text as English fallback")
        void shouldHandlePlainText() {
            LocalizedText result = converter.convert("Just a plain string");

            assertThat(result.getDefault()).isEqualTo("Just a plain string");
        }

        @Test
        @DisplayName("should handle empty string")
        void shouldHandleEmptyString() {
            LocalizedText result = converter.convert("");

            assertThat(result.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("roundtrip")
    class Roundtrip {

        @Test
        @DisplayName("should roundtrip through JSON converters")
        void shouldRoundtripJson() {
            JsonToLocalizedTextConverter reader = new JsonToLocalizedTextConverter();
            LocalizedTextToJsonConverter writer = new LocalizedTextToJsonConverter();

            LocalizedText original = new LocalizedText(Map.of("en", "Hello", "pt-br", "Olá"));
            Json json = writer.convert(original);
            LocalizedText restored = reader.convert(json);

            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("should roundtrip through String converters")
        void shouldRoundtripString() {
            StringToLocalizedTextConverter reader = new StringToLocalizedTextConverter();
            LocalizedTextToStringConverter writer = new LocalizedTextToStringConverter();

            LocalizedText original = LocalizedText.ofEnglish("Test");
            String str = writer.convert(original);
            LocalizedText restored = reader.convert(str);

            assertThat(restored).isEqualTo(original);
        }
    }
}
