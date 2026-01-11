package dev.catananti.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Configuration;

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

    public HealthMetricsExportConfiguration(MeterRegistry registry, HealthEndpoint healthEndpoint) {
        // Register overall health gauge
        Gauge.builder("application.health", healthEndpoint, this::getStatusCode)
                .description("Application health status (3=UP, 2=OUT_OF_SERVICE, 1=DOWN, 0=UNKNOWN)")
                .tag("type", "overall")
                .strongReference(true)
                .register(registry);
    }

    // TODO F-052: HealthEndpoint.health() may block; consider using ReactiveHealthEndpointWebExtension
    //             for fully non-blocking health metric export
    private int getStatusCode(HealthEndpoint health) {
        try {
            Status status = health.health().getStatus();
            if (Status.UP.equals(status)) {
                return 3;
            }
            if (Status.OUT_OF_SERVICE.equals(status)) {
                return 2;
            }
            if (Status.DOWN.equals(status)) {
                return 1;
            }
        } catch (Exception e) {
            // If health check fails, return UNKNOWN
        }
        return 0;
    }
}
