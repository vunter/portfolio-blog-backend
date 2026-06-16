package dev.catananti.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.LinkedHashMap;
import java.util.Map;
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
 *
 * OBS-7: In addition to the overall {@code application.health{type="overall"}} gauge, a per-component
 * gauge is exported for each monitored dependency ({@code application.health{type="db"|"redis"}}) so
 * operators can tell which dependency is down rather than only seeing an aggregate DOWN.
 */
@Configuration(proxyBeanMethods = false)
public class HealthMetricsExportConfiguration {

    /** Components exported as their own tagged gauge, in addition to the overall status. */
    private static final String[] MONITORED_COMPONENTS = {"db", "redis"};

    private final AtomicInteger cachedStatus = new AtomicInteger(statusToCode(Status.UP));
    private final Map<String, AtomicInteger> componentStatus = new LinkedHashMap<>();
    private final HealthEndpoint healthEndpoint;

    public HealthMetricsExportConfiguration(MeterRegistry registry, HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;

        Gauge.builder("application.health", cachedStatus, AtomicInteger::get)
                .description("Application health status (3=UP, 2=OUT_OF_SERVICE, 1=DOWN, 0=UNKNOWN)")
                .tag("type", "overall")
                .strongReference(true)
                .register(registry);

        // OBS-7: one tagged gauge per dependency so a single failing component is attributable.
        // Seed optimistically to UP so the gauge does not sit at 0/UNKNOWN during the startup window
        // and falsely trip ServiceDown before the first scheduled reading lands.
        for (String component : MONITORED_COMPONENTS) {
            AtomicInteger status = new AtomicInteger(statusToCode(Status.UP));
            componentStatus.put(component, status);
            Gauge.builder("application.health", status, AtomicInteger::get)
                    .description("Component health status (3=UP, 2=OUT_OF_SERVICE, 1=DOWN, 0=UNKNOWN)")
                    .tag("type", component)
                    .strongReference(true)
                    .register(registry);
        }
    }

    @Scheduled(fixedRateString = "${scheduling.health-metrics-ms:15000}",
            initialDelayString = "${scheduling.health-initial-delay-ms:5000}")
    public void refreshHealthStatus() {
        // OBS-7: use a short, dedicated initial delay (default 5s) rather than the shared 30s
        // scheduling.initial-delay-ms so the seeded gauges are corrected with a real reading quickly.
        reactor.core.publisher.Mono.fromRunnable(this::readAndCacheHealth)
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(
                        unused -> {},
                        e -> cachedStatus.set(statusToCode(Status.UNKNOWN))
                );
    }

    private void readAndCacheHealth() {
        cachedStatus.set(statusToCode(healthEndpoint.health().getStatus()));
        // healthForPath returns null when a component is absent (e.g. redis disabled in a profile);
        // leave that gauge untouched so it doesn't flap to UNKNOWN for a dependency we don't run.
        componentStatus.forEach((component, holder) -> {
            HealthDescriptor descriptor = healthEndpoint.healthForPath(component);
            if (descriptor != null) {
                holder.set(statusToCode(descriptor.getStatus()));
            }
        });
    }

    private static int statusToCode(Status status) {
        if (Status.UP.equals(status)) {
            return 3;
        } else if (Status.OUT_OF_SERVICE.equals(status)) {
            return 2;
        } else if (Status.DOWN.equals(status)) {
            return 1;
        }
        return 0;
    }
}
