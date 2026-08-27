package dev.catananti.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableCaching
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
@Slf4j
public class RedisConfig {

    // F-020: ReactiveRedisTemplate beans are defined below for WebFlux compatibility
    // F-021: Connection pool tuning is configured via application.properties
    //        (spring.data.redis.lettuce.pool.max-active, max-idle, min-idle, max-wait)
    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        log.info("Initializing Redis cache manager");
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    @Bean
    @Primary
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        log.info("Creating reactive Redis template");
        
        StringRedisSerializer serializer = new StringRedisSerializer();
        RedisSerializationContext<String, String> serializationContext = 
                RedisSerializationContext.<String, String>newSerializationContext(serializer)
                        .key(serializer)
                        .value(serializer)
                        .hashKey(serializer)
                        .hashValue(serializer)
                        .build();

        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }

    @Bean
    public ReactiveRedisTemplate<String, Object> reactiveRedisObjectTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        // The no-arg constructor builds its own ObjectMapper, so the application's
        // @Primary one — and with it the JavaTimeModule — never reaches this path.
        // Every DTO cached here carries LocalDateTime fields, and Jackson refuses to
        // write them without the module (REQUIRE_HANDLERS_FOR_JAVA8_TIMES), so every
        // write failed. CacheService swallows the error and falls back to the source,
        // which is why this surfaced only as a WARN: the cache was simply never
        // populated. It stayed invisible in production because that database has no
        // articles yet, and an empty page carries no date to serialize.
        //
        // configure() reaches the mapper Spring already built, keeping its default
        // typing (the @class hints PageResponse<ArticleResponse> needs to deserialize
        // into real DTOs instead of maps) and its NullValue serializer. Passing a
        // mapper to the constructor instead would drop the typing and mutate the
        // shared @Primary mapper.
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer()
                .configure(mapper -> {
                    mapper.registerModule(JacksonConfig.utcJavaTimeModule());
                    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                });
        
        RedisSerializationContext<String, Object> serializationContext = 
                RedisSerializationContext.<String, Object>newSerializationContext(keySerializer)
                        .key(keySerializer)
                        .value(valueSerializer)
                        .hashKey(keySerializer)
                        .hashValue(valueSerializer)
                        .build();

        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }
}
