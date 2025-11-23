package dev.catananti.config.converter;

import dev.catananti.entity.LocalizedText;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

/**
 * R2DBC Writing converter: LocalizedText → PostgreSQL JSONB (Json).
 */
@WritingConverter
public class LocalizedTextToJsonConverter implements Converter<LocalizedText, Json> {

    @Override
    public Json convert(LocalizedText source) {
        return Json.of(source.toJson());
    }
}
