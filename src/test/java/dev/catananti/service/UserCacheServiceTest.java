package dev.catananti.service;

import dev.catananti.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserCacheService")
class UserCacheServiceTest {

    private UserCacheService service;

    @BeforeEach
    void setUp() {
        service = new UserCacheService();
    }

    @Test
    @DisplayName("should return null for cache miss")
    void shouldReturnNullForCacheMiss() {
        assertThat(service.getIfPresent(99L)).isNull();
    }

    @Test
    @DisplayName("should return null when querying with null id")
    void shouldReturnNullForNullKey() {
        assertThat(service.getIfPresent(null)).isNull();
    }

    @Test
    @DisplayName("should cache and retrieve user")
    void shouldCacheAndRetrieveUser() {
        User user = User.builder().id(1L).email("user@test.com").name("Test User").build();
        service.put(1L, user);

        User cached = service.getIfPresent(1L);
        assertThat(cached).isNotNull();
        assertThat(cached.getEmail()).isEqualTo("user@test.com");
        assertThat(cached.getName()).isEqualTo("Test User");
    }

    @Test
    @DisplayName("should evict user from cache")
    void shouldEvictUser() {
        User user = User.builder().id(2L).email("evict@test.com").name("Evict Me").build();
        service.put(2L, user);

        assertThat(service.getIfPresent(2L)).isNotNull();

        service.evict(2L);

        assertThat(service.getIfPresent(2L)).isNull();
    }

    @Test
    @DisplayName("should handle eviction of non-existent key gracefully")
    void shouldHandleEvictionOfNonExistentKey() {
        // Should not throw
        service.evict(404L);
    }

    @Test
    @DisplayName("should ignore null keys on put and evict")
    void shouldIgnoreNullKeys() {
        // Neither should throw
        service.put(null, User.builder().id(1L).build());
        service.evict(null);
    }

    @Test
    @DisplayName("should overwrite existing cache entry")
    void shouldOverwriteExistingEntry() {
        User user1 = User.builder().id(1L).email("user@test.com").name("Name1").build();
        User user2 = User.builder().id(1L).email("user@test.com").name("Name2").build();

        service.put(1L, user1);
        service.put(1L, user2);

        User cached = service.getIfPresent(1L);
        assertThat(cached).isNotNull();
        assertThat(cached.getName()).isEqualTo("Name2");
    }
}
