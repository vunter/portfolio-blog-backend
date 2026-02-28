package dev.catananti.config;

import dev.catananti.util.IpAddressExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.Set;

/**
 * Configuration class to externalize trusted proxy list from application.properties.
 * Sets the trusted proxies on the static IpAddressExtractor utility at startup.
 */
@Configuration
@Slf4j
public class TrustedProxyConfig {

    @Value("${app.trusted-proxies:127.0.0.1,::1,0:0:0:0:0:0:0:1}")
    private String trustedProxies;

    @PostConstruct
    public void configureTrustedProxies() {
        Set<String> proxies = Set.of(trustedProxies.split(","));
        IpAddressExtractor.setTrustedProxies(proxies);
        log.info("Trusted proxies configured: {}", proxies.size());
    }
}
