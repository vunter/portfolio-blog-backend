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
                    // Fail CLOSED: if Redis is unavailable we cannot guarantee mutual
                    // exclusion across replicas, so we skip this run rather than letting
                    // every replica proceed (which would, e.g., blast duplicate
                    // "new article" e-mails to every subscriber). A missed cleanup/publish
                    // cycle is recovered on the next tick; a duplicate side-effect is not.
                    log.warn("Redis unavailable for scheduler lock '{}', skipping run to avoid duplicate side-effects",
                            lockName);
                    return Mono.just(false);
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
