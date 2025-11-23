package dev.catananti.config.converter;

import dev.catananti.entity.LocalizedText;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

/**
 * R2DBC Reading converter: PostgreSQL JSONB (Json) → LocalizedText.
 */
@ReadingConverter
public class JsonToLocalizedTextConverter implements Converter<Json, LocalizedText> {

    @Override
    public LocalizedText convert(Json source) {
        return LocalizedText.fromJson(source.asString());
    }
}
