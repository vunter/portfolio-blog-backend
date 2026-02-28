package dev.catananti.config.converter;

import dev.catananti.entity.LocalizedText;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

/**
 * R2DBC Writing converter for H2: LocalizedText → String (JSON text).
 */
@WritingConverter
public class LocalizedTextToStringConverter implements Converter<LocalizedText, String> {

    @Override
    public String convert(LocalizedText source) {
        return source.toJson();
    }
}
