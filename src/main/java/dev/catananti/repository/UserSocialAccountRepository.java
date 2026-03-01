package dev.catananti.repository;

import dev.catananti.entity.UserSocialAccount;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserSocialAccountRepository extends R2dbcRepository<UserSocialAccount, Long> {

    Mono<UserSocialAccount> findByProviderAndProviderId(String provider, String providerId);

    Flux<UserSocialAccount> findByUserId(Long userId);

    Mono<UserSocialAccount> findByUserIdAndProvider(Long userId, String provider);

    Mono<Void> deleteByUserIdAndProvider(Long userId, String provider);

    Mono<Long> countByUserId(Long userId);
}
