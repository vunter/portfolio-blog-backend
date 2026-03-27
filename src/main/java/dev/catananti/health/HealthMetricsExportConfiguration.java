package dev.catananti.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration to export health indicators as Micrometer metrics.
 * This allows monitoring systems like Prometheus/Datadog to track health status.
 *
 * Status codes:
 * - UP = 3
 * - OUT_OF_SERVICE = 2
 * - DOWN = 1
 * - UNKNOWN = 0
 */
@Configuration(proxyBeanMethods = false)
public class HealthMetricsExportConfiguration {

    private final AtomicInteger cachedStatus = new AtomicInteger(0);
    private final HealthEndpoint healthEndpoint;

    public HealthMetricsExportConfiguration(MeterRegistry registry, HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;

        Gauge.builder("application.health", cachedStatus, AtomicInteger::get)
                .description("Application health status (3=UP, 2=OUT_OF_SERVICE, 1=DOWN, 0=UNKNOWN)")
                .tag("type", "overall")
                .strongReference(true)
                .register(registry);
    }

    @Scheduled(fixedRateString = "${scheduling.health-metrics-ms:15000}", initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void refreshHealthStatus() {
        reactor.core.publisher.Mono.fromCallable(() -> healthEndpoint.health().getStatus())
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(
                        status -> {
                            int code = 0;
                            if (Status.UP.equals(status)) {
                                code = 3;
                            } else if (Status.OUT_OF_SERVICE.equals(status)) {
                                code = 2;
                            } else if (Status.DOWN.equals(status)) {
                                code = 1;
                            }
                            cachedStatus.set(code);
                        },
                        e -> cachedStatus.set(0)
                );
    }
}
