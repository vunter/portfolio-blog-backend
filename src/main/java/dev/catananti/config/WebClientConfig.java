package dev.catananti.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Centralises the {@link WebClient.Builder} used for outbound HTTP. All
 * production callers should inject this bean rather than instantiating
 * {@code WebClient.builder()} directly so the connector-level timeouts
 * (TCP connect, TLS handshake, read, write) are uniformly enforced. A
 * Reactor-level {@code .timeout(...)} only kicks in after the response
 * has begun arriving; that is too late for a hung peer.
 */
@Configuration(proxyBeanMethods = false)
public class WebClientConfig {

    // Prototype scope: each injection point gets its OWN builder. The builder is
    // mutable, and some callers set defaults on it (e.g. CloudflareEmailRoutingService
    // adds a default Authorization header). With a shared singleton those defaults
    // leaked into every WebClient built afterwards — the Cloudflare token was being
    // sent to api.github.com and returning 401. Prototype isolates each caller, which
    // is also how Spring Boot's own auto-configured WebClient.Builder behaves.
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public WebClient.Builder webClientBuilder(
            @Value("${app.webclient.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.webclient.response-timeout-ms:15000}") int responseTimeoutMs,
            @Value("${app.webclient.read-timeout-ms:30000}") int readTimeoutMs,
            @Value("${app.webclient.write-timeout-ms:15000}") int writeTimeoutMs) {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(writeTimeoutMs, TimeUnit.MILLISECONDS)));

        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
