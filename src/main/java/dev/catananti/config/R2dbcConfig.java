package dev.catananti.config;

import dev.catananti.config.converter.JsonToLocalizedTextConverter;
import dev.catananti.config.converter.LocalizedTextToJsonConverter;
import dev.catananti.config.converter.LocalizedTextToStringConverter;
import dev.catananti.config.converter.StringToLocalizedTextConverter;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.r2dbc.connection.init.CompositeDatabasePopulator;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableR2dbcRepositories(basePackages = "dev.catananti.repository")
public class R2dbcConfig {

    @Value("${app.schema.file:schema.sql}")
    private String schemaFile;

    /**
     * Auto-initialise the database schema on startup.
     * Disabled by default in production (set {@code app.schema.init=true} to enable).
     * Note: R2DBC PostgreSQL driver cannot parse PL/pgSQL {@code DO $$ ... $$} blocks,
     * so production schemas should be applied externally via {@code psql}.
     */
    @Bean
    @ConditionalOnProperty(name = "app.schema.init", havingValue = "true", matchIfMissing = false)
    public ConnectionFactoryInitializer initializer(ConnectionFactory connectionFactory) {
        ConnectionFactoryInitializer initializer = new ConnectionFactoryInitializer();
        initializer.setConnectionFactory(connectionFactory);

        CompositeDatabasePopulator populator = new CompositeDatabasePopulator();
        populator.addPopulators(new ResourceDatabasePopulator(new ClassPathResource(schemaFile)));
        initializer.setDatabasePopulator(populator);

        return initializer;
    }

    @Bean
    @Profile("!dev")
    public R2dbcCustomConversions r2dbcCustomConversions(ConnectionFactory connectionFactory) {
        var dialect = DialectResolver.getDialect(connectionFactory);
        return R2dbcCustomConversions.of(dialect, List.of(
                new JsonToLocalizedTextConverter(),
                new LocalizedTextToJsonConverter()
        ));
    }

    @Bean
    @Profile("dev")
    public R2dbcCustomConversions r2dbcCustomConversionsH2(ConnectionFactory connectionFactory) {
        var dialect = DialectResolver.getDialect(connectionFactory);
        return R2dbcCustomConversions.of(dialect, List.of(
                new StringToLocalizedTextConverter(),
                new LocalizedTextToStringConverter()
        ));
    }
}
