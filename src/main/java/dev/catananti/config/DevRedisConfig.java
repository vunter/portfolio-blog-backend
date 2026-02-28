package dev.catananti.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Dev profile: provides ReactiveRedisTemplate beans backed by a Lettuce connection factory
 * pointing to localhost:6379. The factory is lazy — it won't fail at startup if Redis is
 * not running. Services already have onErrorResume fallbacks for when Redis is unavailable.
 */
@Configuration(proxyBeanMethods = false)
@Profile({"dev", "e2e"})
@Slf4j
public class DevRedisConfig {

    @Bean
    public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
        log.info("Creating lazy Lettuce connection factory for dev (Redis not required)");
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
        factory.setValidateConnection(false);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    @Primary
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        log.info("Creating dev ReactiveRedisTemplate<String, String>");
        StringRedisSerializer serializer = new StringRedisSerializer();
        RedisSerializationContext<String, String> ctx =
                RedisSerializationContext.<String, String>newSerializationContext(serializer)
                        .key(serializer)
                        .value(serializer)
                        .hashKey(serializer)
                        .hashValue(serializer)
                        .build();
        return new ReactiveRedisTemplate<>(connectionFactory, ctx);
    }

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        log.info("Creating dev ReactiveStringRedisTemplate");
        return new ReactiveStringRedisTemplate(connectionFactory);
    }

    @Bean
    public ReactiveRedisTemplate<String, Object> reactiveRedisObjectTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        log.info("Creating dev ReactiveRedisTemplate<String, Object>");
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        RedisSerializationContext<String, Object> ctx =
                RedisSerializationContext.<String, Object>newSerializationContext(keySerializer)
                        .key(RedisSerializationContext.SerializationPair.fromSerializer(keySerializer))
                        .value(RedisSerializationContext.SerializationPair.raw())
                        .hashKey(RedisSerializationContext.SerializationPair.fromSerializer(keySerializer))
                        .hashValue(RedisSerializationContext.SerializationPair.raw())
                        .build();
        return new ReactiveRedisTemplate<>(connectionFactory, ctx);
    }
}
