package dev.catananti.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.r2dbc.dialect.H2Dialect;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * R2DBC configuration for dev profile.
 * Forces H2 dialect to avoid PostgreSQL-style quoting issues.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
public class DevR2dbcConfig {

    /**
     * Force H2 dialect for dev profile.
     * This prevents R2DBC from detecting PostgreSQL mode and using quoted identifiers.
     */
    @Bean
    @Primary
    public R2dbcDialect r2dbcDialect(ConnectionFactory connectionFactory) {
        return H2Dialect.INSTANCE;
    }
}
