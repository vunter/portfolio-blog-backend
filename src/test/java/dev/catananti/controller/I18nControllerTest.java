package dev.catananti.controller;

import dev.catananti.repository.TranslationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class I18nControllerTest {

    @Mock
    private TranslationRepository translationRepository;

    @InjectMocks
    private I18nController controller;

    @Nested
    @DisplayName("GET /api/v1/i18n/{locale}")
    class GetTranslations {

        @Test
        @DisplayName("Should return public translations for anonymous user")
        void shouldReturnPublicTranslationsForAnonymousUser() {
            Map<String, String> row1 = Map.of("key", "home.title", "value", "Home");
            Map<String, String> row2 = Map.of("key", "home.subtitle", "value", "Welcome");

            when(translationRepository.findByLocaleAndVisibility(eq("en"), eq(List.of("public"))))
                    .thenReturn(Flux.just(row1, row2));

            StepVerifier.create(controller.getTranslations("en", Mono.empty()))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, String> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body).hasSize(2);
                        assertThat(body.get("home.title")).isEqualTo("Home");
                        assertThat(body.get("home.subtitle")).isEqualTo("Welcome");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return admin-tier translations for admin user")
        void shouldReturnAdminTierTranslationsForAdmin() {
            Authentication adminAuth = new UsernamePasswordAuthenticationToken(
                    "admin@test.com", "password",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );

            Map<String, String> row = Map.of("key", "admin.dashboard", "value", "Dashboard");

            when(translationRepository.findByLocaleAndVisibility(
                    eq("en"), eq(List.of("public", "viewer", "dev", "admin"))))
                    .thenReturn(Flux.just(row));

            StepVerifier.create(controller.getTranslations("en", Mono.just(adminAuth)))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        Map<String, String> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("admin.dashboard")).isEqualTo("Dashboard");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return viewer-tier translations for viewer user")
        void shouldReturnViewerTierTranslationsForViewer() {
            Authentication viewerAuth = new UsernamePasswordAuthenticationToken(
                    "viewer@test.com", "password",
                    List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
            );

            when(translationRepository.findByLocaleAndVisibility(
                    eq("en"), eq(List.of("public", "viewer"))))
                    .thenReturn(Flux.empty());

            StepVerifier.create(controller.getTranslations("en", Mono.just(viewerAuth)))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody()).isEmpty();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should return empty map when no translations exist")
        void shouldReturnEmptyMapWhenNoTranslations() {
            when(translationRepository.findByLocaleAndVisibility(eq("fr"), eq(List.of("public"))))
                    .thenReturn(Flux.empty());

            StepVerifier.create(controller.getTranslations("fr", Mono.empty()))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody()).isEmpty();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Cache Invalidation")
    class CacheInvalidation {

        @Test
        @DisplayName("Should invalidate cache and return ok status")
        void shouldInvalidateCache() {
            StepVerifier.create(controller.invalidateCache())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(response.getBody()).containsEntry("status", "cache invalidated");
                    })
                    .verifyComplete();
        }
    }
}
