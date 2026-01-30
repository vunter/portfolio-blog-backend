package dev.catananti.controller;

import dev.catananti.service.SiteSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminSettingsControllerTest {

    @Mock
    private SiteSettingsService settingsService;

    @InjectMocks
    private AdminSettingsController controller;

    @Nested
    @DisplayName("GET /api/v1/admin/settings")
    class GetSettings {

        @Test
        @DisplayName("Should return application settings")
        void shouldReturnSettings() {
            Map<String, Object> settings = Map.of(
                    "siteName", "My Blog",
                    "siteDescription", "A tech blog",
                    "postsPerPage", 10,
                    "enableComments", true
            );

            when(settingsService.getAllSettings()).thenReturn(Mono.just(settings));

            StepVerifier.create(controller.getSettings())
                    .assertNext(result -> {
                        assertThat(result).containsEntry("siteName", "My Blog");
                        assertThat(result).containsEntry("postsPerPage", 10);
                        assertThat(result).containsEntry("enableComments", true);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/settings")
    class UpdateSettings {

        @Test
        @DisplayName("Should update settings")
        void shouldUpdateSettings() {
            Map<String, Object> input = Map.of(
                    "siteName", "Updated Blog",
                    "postsPerPage", 15
            );

            Map<String, Object> updated = Map.of(
                    "siteName", "Updated Blog",
                    "siteDescription", "A tech blog",
                    "postsPerPage", 15,
                    "enableComments", true
            );

            when(settingsService.updateSettings(anyMap())).thenReturn(Mono.just(updated));

            StepVerifier.create(controller.updateSettings(input))
                    .assertNext(result -> {
                        assertThat(result).containsEntry("siteName", "Updated Blog");
                        assertThat(result).containsEntry("postsPerPage", 15);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject too many settings entries")
        void shouldRejectTooManyEntries() {
            Map<String, Object> tooMany = new HashMap<>();
            for (int i = 0; i < 51; i++) {
                tooMany.put("key" + i, "value" + i);
            }

            StepVerifier.create(controller.updateSettings(tooMany))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                            && ex.getMessage().contains("Too many settings entries"))
                    .verify();

            verifyNoInteractions(settingsService);
        }
    }
}
