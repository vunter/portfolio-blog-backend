package dev.catananti.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

/**
 * Custom virtual thread configuration for @Async tasks.
 * 
 * Note: Spring Boot 4.1+ enables virtual threads by default via
 * {@code spring.threads.virtual.enabled=true}. This config customises
 * the application task executor used by @Async and @Scheduled methods
 * to use a named virtual thread factory for better observability.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@Slf4j
public class VirtualThreadConfig {

    /**
     * Replace the default application task executor with virtual threads.
     * This affects @Async methods and scheduled tasks.
     */
    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    public AsyncTaskExecutor applicationTaskExecutor() {
        log.info("Configuring virtual thread executor for async tasks");
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Virtual thread factory for custom executors.
     * Use this when you need to create virtual threads programmatically.
     */
    @Bean
    public Thread.Builder.OfVirtual virtualThreadBuilder() {
        return Thread.ofVirtual().name("blog-vt-", 0);
    }
}
