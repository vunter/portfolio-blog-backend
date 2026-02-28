package dev.catananti.config.converter;

import dev.catananti.entity.LocalizedText;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

/**
 * R2DBC Reading converter for H2: String → LocalizedText.
 */
@ReadingConverter
public class StringToLocalizedTextConverter implements Converter<String, LocalizedText> {

    @Override
    public LocalizedText convert(String source) {
        return LocalizedText.fromJson(source);
    }
}
