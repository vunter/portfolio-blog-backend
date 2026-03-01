package dev.catananti.repository;

import dev.catananti.entity.User;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserRepository extends ReactiveCrudRepository<User, Long> {

    @Query("SELECT * FROM users WHERE email = :email")
    Mono<User> findByEmail(String email);

    @Query("SELECT COUNT(*) > 0 FROM users WHERE email = :email")
    Mono<Boolean> existsByEmail(String email);

    @Query("SELECT * FROM users WHERE LOWER(username) = LOWER(:username)")
    Mono<User> findByUsername(String username);

    @Query("SELECT COUNT(*) > 0 FROM users WHERE LOWER(username) = LOWER(:username)")
    Mono<Boolean> existsByUsername(String username);

    @Query("SELECT * FROM users WHERE role = :role")
    Flux<User> findByRole(String role);

    @Query("SELECT * FROM users ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<User> findAllPaged(int limit, int offset);

    @Query("SELECT COUNT(*) FROM users")
    Mono<Long> countAll();

    @Query("SELECT * FROM users WHERE LOWER(name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(email) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(role) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<User> searchUsers(String search, int limit, int offset);

    @Query("SELECT COUNT(*) FROM users WHERE LOWER(name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(email) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(role) LIKE LOWER(CONCAT('%', :search, '%'))")
    Mono<Long> countSearch(String search);

    @Query("SELECT COUNT(*) FROM users WHERE role = :role")
    Mono<Long> countByRole(String role);
}
