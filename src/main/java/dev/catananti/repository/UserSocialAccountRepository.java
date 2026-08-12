package dev.catananti.repository;

import dev.catananti.entity.UserSocialAccount;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserSocialAccountRepository extends R2dbcRepository<UserSocialAccount, Long> {

    Mono<UserSocialAccount> findByProviderAndProviderId(String provider, String providerId);

    Flux<UserSocialAccount> findByUserId(Long userId);

    Mono<Void> deleteByUserIdAndProvider(Long userId, String provider);

    /**
     * TX-06: single-statement guarded delete — refuses to remove the user's last
     * social account, closing the check-then-delete race between two concurrent
     * unlink requests. Returns the number of rows deleted (0 = not found OR last one).
     */
    @org.springframework.data.r2dbc.repository.Modifying
    @org.springframework.data.r2dbc.repository.Query("""
            DELETE FROM user_social_accounts
            WHERE user_id = :userId AND provider = :provider
              AND (SELECT COUNT(*) FROM user_social_accounts WHERE user_id = :userId) > 1
            """)
    Mono<Long> deleteByUserIdAndProviderIfNotLast(Long userId, String provider);

    Mono<Long> countByUserId(Long userId);
}
