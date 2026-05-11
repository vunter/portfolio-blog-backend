package dev.catananti.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@Slf4j
public class SchedulerLock {

    private final ReactiveStringRedisTemplate redisTemplate;

    public SchedulerLock(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<Void> executeWithLock(String lockName, Duration lockTtl, Mono<Void> task) {
        if (redisTemplate == null) {
            return task;
        }
        String lockKey = "scheduler:" + lockName + ":lock";
        return redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", lockTtl)
                .onErrorResume(e -> {
                    log.debug("Redis unavailable for scheduler lock '{}', proceeding without lock", lockName);
                    return Mono.just(true);
                })
                .flatMap(acquired -> {
                    if (!Boolean.TRUE.equals(acquired)) {
                        log.debug("Skipping '{}' — another instance holds the lock", lockName);
                        return Mono.empty();
                    }
                    return task;
                });
    }
}
