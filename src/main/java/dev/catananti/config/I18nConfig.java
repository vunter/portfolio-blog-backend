package dev.catananti.config;

import dev.catananti.repository.TranslationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.MessageSource;
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver;
import org.springframework.web.server.i18n.LocaleContextResolver;

import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class I18nConfig {

    @Bean
    public MessageSource messageSource(TranslationRepository translationRepository) {
        return new DbMessageSource(translationRepository);
    }

    @Bean
    public LocaleContextResolver localeContextResolver() {
        AcceptHeaderLocaleContextResolver resolver = new AcceptHeaderLocaleContextResolver();
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setSupportedLocales(LocaleConstants.SUPPORTED_LOCALES);
        return resolver;
    }
}
