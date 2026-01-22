package dev.catananti.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceMonitoringAspect Tests")
class PerformanceMonitoringAspectTest {

    private PerformanceMonitoringAspect aspect;
    private MeterRegistry meterRegistry;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        aspect = new PerformanceMonitoringAspect(meterRegistry);
    }

    @Test
    @DisplayName("Should monitor service method execution returning Mono")
    void shouldMonitorServiceMethodReturningMono() throws Throwable {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn((Class) ArticleServiceMock.class);
        when(signature.getName()).thenReturn("findById");
        when(joinPoint.proceed()).thenReturn(Mono.just("result"));

        Object result = aspect.monitorServiceMethod(joinPoint);

        assertThat(result).isInstanceOf(Mono.class);
        
        @SuppressWarnings("unchecked")
        Mono<String> mono = (Mono<String>) result;
        StepVerifier.create(mono)
                .expectNext("result")
                .verifyComplete();

        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("Should monitor service method execution returning Flux")
    void shouldMonitorServiceMethodReturningFlux() throws Throwable {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn((Class) ArticleServiceMock.class);
        when(signature.getName()).thenReturn("findAll");
        when(joinPoint.proceed()).thenReturn(Flux.just("article1", "article2"));

        Object result = aspect.monitorServiceMethod(joinPoint);

        assertThat(result).isInstanceOf(Flux.class);
        
        @SuppressWarnings("unchecked")
        Flux<String> flux = (Flux<String>) result;
        StepVerifier.create(flux)
                .expectNext("article1", "article2")
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle Mono errors in monitored methods")
    void shouldHandleMonoErrors() throws Throwable {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn((Class) UserServiceMock.class);
        when(signature.getName()).thenReturn("findByUsername");
        when(joinPoint.proceed()).thenReturn(Mono.error(new RuntimeException("User not found")));

        Object result = aspect.monitorServiceMethod(joinPoint);

        assertThat(result).isInstanceOf(Mono.class);
        
        @SuppressWarnings("unchecked")
        Mono<String> mono = (Mono<String>) result;
        StepVerifier.create(mono)
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName("Should handle non-reactive return types")
    void shouldHandleNonReactiveReturnTypes() throws Throwable {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn((Class) TestServiceMock.class);
        when(signature.getName()).thenReturn("syncMethod");
        when(joinPoint.proceed()).thenReturn("sync result");

        Object result = aspect.monitorServiceMethod(joinPoint);

        assertThat(result).isEqualTo("sync result");
    }

    @Test
    @DisplayName("Should handle null return")
    void shouldHandleNullReturn() throws Throwable {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn((Class) VoidServiceMock.class);
        when(signature.getName()).thenReturn("voidMethod");
        when(joinPoint.proceed()).thenReturn(null);

        Object result = aspect.monitorServiceMethod(joinPoint);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should monitor repository methods")
    void shouldMonitorRepositoryMethods() throws Throwable {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn((Class) ArticleRepositoryMock.class);
        when(signature.getName()).thenReturn("findAll");
        when(joinPoint.proceed()).thenReturn(Mono.just("articles"));

        Object result = aspect.monitorRepositoryMethod(joinPoint);

        assertThat(result).isInstanceOf(Mono.class);
        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("Should register metrics")
    void shouldRegisterMetrics() throws Throwable {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn((Class) ArticleServiceMock.class);
        when(signature.getName()).thenReturn("getArticle");
        when(joinPoint.proceed()).thenReturn(Mono.just("article"));

        Object result = aspect.monitorServiceMethod(joinPoint);

        @SuppressWarnings("unchecked")
        Mono<String> mono = (Mono<String>) result;
        StepVerifier.create(mono)
                .expectNext("article")
                .verifyComplete();

        // Verify metrics were recorded
        assertThat(meterRegistry.getMeters()).isNotEmpty();
    }

    // Mock classes to satisfy getDeclaringType()
    private static class ArticleServiceMock {}
    private static class UserServiceMock {}
    private static class TestServiceMock {}
    private static class VoidServiceMock {}
    private static class ArticleRepositoryMock {}
}
