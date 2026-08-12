package dev.catananti.repository;

import dev.catananti.entity.SiteSetting;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SiteSettingRepository extends ReactiveCrudRepository<SiteSetting, Long> {

    Mono<SiteSetting> findBySettingKey(String settingKey);

    Flux<SiteSetting> findAllByOrderBySettingKeyAsc();

}
