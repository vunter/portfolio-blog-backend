package dev.catananti.controller;

import dev.catananti.service.EmailTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailTemplatePreviewController Tests")
class EmailTemplatePreviewControllerTest {

    @Mock
    private EmailTemplateService templateService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        EmailTemplatePreviewController controller = new EmailTemplatePreviewController(templateService);

        var auth = new UsernamePasswordAuthenticationToken("admin@test.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var secCtx = new SecurityContextImpl(auth);
        WebFilter secFilter = (exchange, chain) -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(secCtx)));

        webTestClient = WebTestClient.bindToController(controller)
                .webFilter(secFilter)
                .configureClient().build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/settings/email-templates")
    class ListTemplates {

        @Test
        @DisplayName("Should return list of available email templates")
        void shouldReturnTemplateList() {
            when(templateService.getOverriddenTemplateIds()).thenReturn(Flux.just("password-reset"));

            webTestClient
                    .get().uri("/api/v1/admin/settings/email-templates")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$").isArray()
                    .jsonPath("$[0].id").exists()
                    .jsonPath("$[0].name").exists()
                    .jsonPath("$[0].description").exists()
                    .jsonPath("$[0].customized").exists();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/settings/email-templates/{templateId}/preview")
    class PreviewTemplate {

        @Test
        @DisplayName("Should return rendered HTML preview for default template")
        void shouldReturnHtmlPreview() {
            when(templateService.ensureCacheLoaded()).thenReturn(Mono.empty());
            EmailTemplateService.TemplateSource source =
                    new EmailTemplateService.TemplateSource("<html>default</html>", false);
            when(templateService.getTemplateSource("registration-welcome")).thenReturn(Mono.just(source));
            when(templateService.render(eq("registration-welcome"), any()))
                    .thenReturn("<html>rendered</html>");

            webTestClient
                    .get().uri("/api/v1/admin/settings/email-templates/registration-welcome/preview")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                    .expectBody(String.class).isEqualTo("<html>rendered</html>");
        }

        @Test
        @DisplayName("Should return 404 for unknown template")
        void shouldReturn404ForUnknownTemplate() {
            webTestClient
                    .get().uri("/api/v1/admin/settings/email-templates/non-existent/preview")
                    .exchange()
                    .expectStatus().isNotFound();
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/admin/settings/email-templates/{templateId}")
    class UpdateTemplate {

        @Test
        @DisplayName("Should save template override")
        void shouldSaveTemplateOverride() {
            when(templateService.saveOverride(eq("registration-welcome"), anyString(), anyString()))
                    .thenReturn(Mono.empty());

            webTestClient
                    .put().uri("/api/v1/admin/settings/email-templates/registration-welcome")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("html", "<html>custom</html>"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.message").isEqualTo("Template saved")
                    .jsonPath("$.templateId").isEqualTo("registration-welcome");
        }

        @Test
        @DisplayName("Should return 400 when html is missing")
        void shouldReturn400WhenHtmlMissing() {
            webTestClient
                    .put().uri("/api/v1/admin/settings/email-templates/registration-welcome")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("other", "value"))
                    .exchange()
                    .expectStatus().isBadRequest();
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/admin/settings/email-templates/{templateId}")
    class DeleteTemplate {

        @Test
        @DisplayName("Should delete template override and revert to default")
        void shouldDeleteTemplateOverride() {
            when(templateService.deleteOverride("registration-welcome")).thenReturn(Mono.just(true));

            webTestClient
                    .delete().uri("/api/v1/admin/settings/email-templates/registration-welcome")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.message").isEqualTo("Template reverted to default");
        }

        @Test
        @DisplayName("Should return message when no override exists")
        void shouldReturnMessageWhenNoOverride() {
            when(templateService.deleteOverride("registration-welcome")).thenReturn(Mono.just(false));

            webTestClient
                    .delete().uri("/api/v1/admin/settings/email-templates/registration-welcome")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.message").isEqualTo("No override found");
        }
    }
}
