package dev.catananti.controller;

import dev.catananti.security.JwtAuthenticationFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@TestConfiguration
@EnableWebFluxSecurity
public class TestSecurityConfig {

    @Bean
    @Primary
    public SecurityWebFilterChain testSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/api/v1/articles/**").permitAll()
                        .pathMatchers("/api/v1/tags/**").permitAll()
                        .pathMatchers("/api/v1/analytics/**").permitAll()
                        .pathMatchers("/api/v1/admin/**").authenticated()
                        .anyExchange().permitAll()
                )
                .build();
    }

    @Bean
    @Primary
    public JwtAuthenticationFilter mockJwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(null, null, null) {
            @Override
            public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange, 
                                      org.springframework.web.server.WebFilterChain chain) {
                return chain.filter(exchange);
            }
        };
    }
}
