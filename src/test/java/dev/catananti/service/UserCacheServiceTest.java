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
        assertThat(service.getIfPresent("unknown@test.com")).isNull();
    }

    @Test
    @DisplayName("should cache and retrieve user")
    void shouldCacheAndRetrieveUser() {
        User user = User.builder().id(1L).email("user@test.com").name("Test User").build();
        service.put("user@test.com", user);

        User cached = service.getIfPresent("user@test.com");
        assertThat(cached).isNotNull();
        assertThat(cached.getEmail()).isEqualTo("user@test.com");
        assertThat(cached.getName()).isEqualTo("Test User");
    }

    @Test
    @DisplayName("should evict user from cache")
    void shouldEvictUser() {
        User user = User.builder().id(1L).email("evict@test.com").name("Evict Me").build();
        service.put("evict@test.com", user);

        assertThat(service.getIfPresent("evict@test.com")).isNotNull();

        service.evict("evict@test.com");

        assertThat(service.getIfPresent("evict@test.com")).isNull();
    }

    @Test
    @DisplayName("should handle eviction of non-existent key gracefully")
    void shouldHandleEvictionOfNonExistentKey() {
        // Should not throw
        service.evict("nonexistent@test.com");
    }

    @Test
    @DisplayName("should overwrite existing cache entry")
    void shouldOverwriteExistingEntry() {
        User user1 = User.builder().id(1L).email("user@test.com").name("Name1").build();
        User user2 = User.builder().id(1L).email("user@test.com").name("Name2").build();

        service.put("user@test.com", user1);
        service.put("user@test.com", user2);

        User cached = service.getIfPresent("user@test.com");
        assertThat(cached).isNotNull();
        assertThat(cached.getName()).isEqualTo("Name2");
    }
}
