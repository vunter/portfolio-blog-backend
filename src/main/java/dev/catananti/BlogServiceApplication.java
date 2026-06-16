package dev.catananti;

import dev.catananti.config.RequestIdFilter;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

import reactor.core.publisher.Hooks;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class BlogServiceApplication {

    /** Reactor Context key written by {@link RequestIdFilter} for the correlation ID. */
    private static final String CORRELATION_ID_CONTEXT_KEY = "correlationId";

    public static void main(String[] args) {
        // OBS-2a: Bridge Reactor Context -> SLF4J MDC so that requestId/correlationId
        // written into the Reactor Context by RequestIdFilter are restored into the
        // MDC around operators running on other threads (service/repository logs).
        // Automatic context propagation hooks the registered ThreadLocalAccessors.
        Hooks.enableAutomaticContextPropagation();
        registerMdcThreadLocalAccessors();

        SpringApplication.run(BlogServiceApplication.class, args);
    }

    /**
     * Registers MDC-backed {@link ThreadLocalAccessor}s for the Reactor Context keys
     * populated by {@link RequestIdFilter}. Each accessor's key matches the exact
     * Reactor Context key, so automatic context propagation copies the value into the
     * SLF4J MDC (and clears it afterwards) around reactive operators.
     */
    private static void registerMdcThreadLocalAccessors() {
        ContextRegistry registry = ContextRegistry.getInstance();
        registry.registerThreadLocalAccessor(new MdcThreadLocalAccessor(RequestIdFilter.REQUEST_ID_CONTEXT_KEY));
        registry.registerThreadLocalAccessor(new MdcThreadLocalAccessor(CORRELATION_ID_CONTEXT_KEY));
    }

    /**
     * {@link ThreadLocalAccessor} that bridges a single Reactor Context key to the
     * SLF4J {@link MDC} entry of the same name.
     */
    private record MdcThreadLocalAccessor(String key) implements ThreadLocalAccessor<String> {

        // The record's implicit String key() accessor covariantly implements
        // ThreadLocalAccessor#key() (declared to return Object), so no explicit
        // override is needed — and an explicit one would be rejected by javac
        // (a record accessor must return the exact component type, String).

        @Override
        public String getValue() {
            return MDC.get(key);
        }

        @Override
        public void setValue(String value) {
            MDC.put(key, value);
        }

        @Override
        public void setValue() {
            MDC.remove(key);
        }
    }
}
