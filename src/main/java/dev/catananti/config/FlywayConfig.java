package dev.catananti.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manual Flyway configuration for Spring Boot 4 reactive (WebFlux/R2DBC).
 * Spring Boot 4 modularized autoconfiguration; the Flyway auto-config module
 * may not be on the classpath. This bean runs migrations explicitly using JDBC.
 */
@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true")
@Slf4j
public class FlywayConfig {

    @Value("${spring.flyway.url}")
    private String url;

    @Value("${spring.flyway.user}")
    private String user;

    @Value("${spring.flyway.password}")
    private String password;

    @Value("${spring.flyway.locations:classpath:db/migration}")
    private String locations;

    @Value("${spring.flyway.baseline-on-migrate:true}")
    private boolean baselineOnMigrate;

    @Value("${spring.flyway.baseline-version:1}")
    private String baselineVersion;

    @Value("${spring.flyway.validate-on-migrate:true}")
    private boolean validateOnMigrate;

    @Bean(initMethod = "migrate")
    public Flyway flyway() {
        log.info("Configuring Flyway: url={}, baseline-version={}, baseline-on-migrate={}",
                url, baselineVersion, baselineOnMigrate);
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations)
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion(baselineVersion)
                .validateOnMigrate(validateOnMigrate)
                .load();
        log.info("Flyway configured successfully. Running migrations...");
        return flyway;
    }
}
