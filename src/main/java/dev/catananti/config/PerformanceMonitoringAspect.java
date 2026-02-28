package dev.catananti.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Performance monitoring aspect for automatic method timing.
 * Uses Micrometer metrics and structured logging.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class PerformanceMonitoringAspect {

    // F-058: Timer cache is bounded by MAX_TIMER_CACHE_SIZE with overflow logging — no eviction needed
    private static final int MAX_TIMER_CACHE_SIZE = 500;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    /**
     * Pointcut for all service methods.
     */
    @Pointcut("execution(* dev.catananti.service.*.*(..))")
    public void serviceMethods() {}

    /**
     * Pointcut for repository methods.
     */
    @Pointcut("execution(* dev.catananti.repository.*.*(..))")
    public void repositoryMethods() {}

    /**
     * MIN-09: Exclude trivial utility services from AOP monitoring.
     * These methods are called very frequently and add overhead without useful metrics.
     */
    @Pointcut("execution(* dev.catananti.service.IdService.*(..))" +
            " || execution(* dev.catananti.service.CacheService.get(..))" +
            " || execution(* dev.catananti.service.CacheService.set(..))" +
            " || execution(* dev.catananti.service.MarkdownService.isMarkdown(..))" +
            " || execution(* dev.catananti.service.HtmlSanitizerService.escapeHtml(..))")
    public void trivialMethods() {}

    /**
     * Monitor service method execution time.
     */
    @Around("serviceMethods() && !trivialMethods()")
    public Object monitorServiceMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "service");
    }

    /**
     * Monitor repository method execution time.
     */
    @Around("repositoryMethods()")
    public Object monitorRepositoryMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorMethod(joinPoint, "repository");
    }

    private Object monitorMethod(ProceedingJoinPoint joinPoint, String layer) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = signature.getName();
        String metricName = layer + "." + className + "." + methodName;

        Object result = joinPoint.proceed();

        // Handle reactive types
        if (result instanceof Mono<?> mono) {
            return wrapMono(mono, metricName, className, methodName);
        } else if (result instanceof Flux<?> flux) {
            return wrapFlux(flux, metricName, className, methodName);
        }

        // Non-reactive fallback (shouldn't happen in reactive app)
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> Mono<T> wrapMono(Mono<T> mono, String metricName, String className, String methodName) {
        return Mono.defer(() -> {
            long startTime = System.nanoTime();
            return mono
                    .doOnSuccess(result -> recordSuccess(metricName, startTime, className, methodName))
                    .doOnError(error -> recordError(metricName, startTime, className, methodName, error));
        });
    }

    @SuppressWarnings("unchecked")
    private <T> Flux<T> wrapFlux(Flux<T> flux, String metricName, String className, String methodName) {
        return Flux.defer(() -> {
            long startTime = System.nanoTime();
            return flux
                    .doOnComplete(() -> recordSuccess(metricName, startTime, className, methodName))
                    .doOnError(error -> recordError(metricName, startTime, className, methodName, error));
        });
    }

    private void recordSuccess(String metricName, long startTime, String className, String methodName) {
        long duration = System.nanoTime() - startTime;
        Timer timer = getOrCreateTimer(metricName, className, methodName, "success");
        timer.record(duration, TimeUnit.NANOSECONDS);

        // Log slow operations (500ms threshold — auth/bcrypt ops normally take ~300ms)
        if (duration > Duration.ofMillis(500).toNanos()) {
            log.warn("Slow operation: {}.{} took {}ms",
                    className, methodName, TimeUnit.NANOSECONDS.toMillis(duration));
        }
    }

    private void recordError(String metricName, long startTime, String className, String methodName, Throwable error) {
        long duration = System.nanoTime() - startTime;
        Timer timer = getOrCreateTimer(metricName, className, methodName, "error");
        timer.record(duration, TimeUnit.NANOSECONDS);

        // Expected business exceptions → WARN; unexpected infrastructure errors → ERROR
        if (isExpectedException(error)) {
            log.warn("Operation failed: {}.{} after {}ms: {}",
                    className, methodName, TimeUnit.NANOSECONDS.toMillis(duration), error.getMessage());
        } else {
            log.error("Operation failed: {}.{} after {}ms: {}",
                    className, methodName, TimeUnit.NANOSECONDS.toMillis(duration), error.getMessage());
        }
    }

    private boolean isExpectedException(Throwable error) {
        return error instanceof dev.catananti.exception.ResourceNotFoundException
                || error instanceof dev.catananti.exception.DuplicateResourceException
                || error instanceof IllegalArgumentException
                || error instanceof DuplicateKeyException
                || error instanceof org.springframework.dao.DataIntegrityViolationException
                || error instanceof org.springframework.security.access.AccessDeniedException
                || error instanceof org.springframework.security.authentication.BadCredentialsException
                || error instanceof org.springframework.web.server.ResponseStatusException
                || (error.getMessage() != null && (
                        error.getMessage().contains("email_already_exists")
                        || error.getMessage().contains("Invalid credentials")
                        || error.getMessage().contains("Unable to connect to Redis")
                        || error.getMessage().contains("Connection has been closed")
                        || error.getMessage().contains("Failed to send email")));
    }

    private Timer getOrCreateTimer(String metricName, String className, String methodName, String status) {
        String key = metricName + "." + status;
        // Bound cache to prevent unbounded growth from dynamic method names
        if (timerCache.size() >= MAX_TIMER_CACHE_SIZE && !timerCache.containsKey(key)) {
            log.warn("Timer cache size limit reached ({}), skipping new timer for {}", MAX_TIMER_CACHE_SIZE, key);
            return Timer.builder("method.execution")
                    .tag("class", className)
                    .tag("method", methodName)
                    .tag("status", status)
                    .description("Method execution time (uncached)")
                    .register(meterRegistry);
        }
        return timerCache.computeIfAbsent(key, k ->
                Timer.builder("method.execution")
                        .tag("class", className)
                        .tag("method", methodName)
                        .tag("status", status)
                        .description("Method execution time")
                        .register(meterRegistry)
        );
    }
}
