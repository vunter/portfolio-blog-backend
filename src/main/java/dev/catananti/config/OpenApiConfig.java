package dev.catananti.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Value("${app.version:1.0.0}")
    private String appVersion;

    @Value("${server.port:8080}")
    private String serverPort;

    // F-023: @OpenAPIDefinition cannot express dynamic server URLs from @Value or SecurityScheme
    // components as cleanly. Imperative @Bean is retained for full configurability.
    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Portfolio Blog API")
                        .description("""
                                RESTful API for Portfolio Blog application.
                                
                                ## Features
                                - Article management (CRUD)
                                - Tag management
                                - Comment system with moderation
                                - Analytics and event tracking
                                - JWT authentication with refresh tokens
                                
                                ## Authentication
                                Protected endpoints require a JWT token in the Authorization header:
                                `Authorization: Bearer <token>`
                                
                                To obtain a token, use the `/api/v1/admin/auth/login` endpoint.
                                """)
                        .version(appVersion)
                        .contact(new Contact()
                                .name("Leonardo Catananti")
                                .email("leonardo.catananti@gmail.com")
                                .url("https://catananti.dev"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Development Server"),
                        new Server()
                                .url("https://api.catananti.dev")
                                .description("Production Server")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT authentication token. Obtain via /api/v1/admin/auth/login")));
    }
}
