package dev.catananti.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
// TODO F-038: Register custom ObjectMapper bean via configureHttpMessageCodecs() for consistent JSON handling
// TODO F-039: Restrict Content-Type negotiation to application/json only via configureContentTypeResolver()
public class WebFluxConfig implements WebFluxConfigurer {

    // CORS is configured in SecurityConfig - do not duplicate here
}
