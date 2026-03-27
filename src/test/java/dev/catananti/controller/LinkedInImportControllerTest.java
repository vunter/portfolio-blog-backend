package dev.catananti.controller;

import dev.catananti.dto.ResumeProfileRequest;
import dev.catananti.service.LinkedInPortabilityService;
import dev.catananti.service.OAuth2Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.SecurityContextServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LinkedInImportController Tests")
class LinkedInImportControllerTest {

    @Mock
    private LinkedInPortabilityService portabilityService;

    @Mock
    private OAuth2Service oAuth2Service;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        LinkedInImportController controller = new LinkedInImportController(portabilityService, oAuth2Service);

        var auth = new UsernamePasswordAuthenticationToken("admin@test.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var secCtx = new SecurityContextImpl(auth);

        // WebFilter that sets both the security context and the exchange principal
        WebFilter secFilter = (exchange, chain) -> {
            ServerWebExchange mutatedExchange = exchange.mutate()
                    .principal(Mono.just(auth))
                    .build();
            return chain.filter(mutatedExchange)
                    .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(secCtx)));
        };

        webTestClient = WebTestClient.bindToController(controller)
                .webFilter(secFilter)
                .configureClient().build();
    }

    @Nested
    @DisplayName("GET /api/v1/resume/import/linkedin/status")
    class GetStatus {

        @Test
        @DisplayName("Should return enabled status when portability is available")
        void shouldReturnEnabledStatus() {
            when(portabilityService.isPortabilityEnabled()).thenReturn(true);

            webTestClient
                    .get().uri("/api/v1/resume/import/linkedin/status")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.enabled").isEqualTo(true)
                    .jsonPath("$.note").isEqualTo("LinkedIn DMA portability import is available.");
        }

        @Test
        @DisplayName("Should return disabled status when portability is not configured")
        void shouldReturnDisabledStatus() {
            when(portabilityService.isPortabilityEnabled()).thenReturn(false);

            webTestClient
                    .get().uri("/api/v1/resume/import/linkedin/status")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.enabled").isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/import/linkedin/authorize")
    class Authorize {

        @Test
        @DisplayName("Should redirect to LinkedIn auth URL when enabled")
        void shouldRedirectToLinkedInAuthUrl() {
            when(portabilityService.isPortabilityEnabled()).thenReturn(true);
            when(portabilityService.getPortabilityAuthUrl(anyString()))
                    .thenReturn("https://www.linkedin.com/oauth/v2/authorization?client_id=test");
            when(oAuth2Service.storeState(anyString())).thenReturn(Mono.empty());

            webTestClient
                    .get().uri("/api/v1/resume/import/linkedin/authorize")
                    .exchange()
                    .expectStatus().isFound()
                    .expectHeader().valueMatches("Location", "https://www\\.linkedin\\.com/.*");
        }

        @Test
        @DisplayName("Should return 503 when portability is disabled")
        void shouldReturn503WhenDisabled() {
            when(portabilityService.isPortabilityEnabled()).thenReturn(false);

            webTestClient
                    .get().uri("/api/v1/resume/import/linkedin/authorize")
                    .exchange()
                    .expectStatus().isEqualTo(503);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/import/linkedin/callback")
    class Callback {

        @Test
        @DisplayName("Should return error for invalid state")
        void shouldReturnErrorForInvalidState() {
            when(oAuth2Service.validateAndConsumeState("bad-state")).thenReturn(Mono.just(false));

            webTestClient
                    .get().uri("/api/v1/resume/import/linkedin/callback?code=code123&state=bad-state")
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        @Test
        @DisplayName("Should handle successful callback and redirect")
        void shouldHandleSuccessfulCallback() {
            when(oAuth2Service.validateAndConsumeState("valid-state")).thenReturn(Mono.just(true));

            Map<String, Object> tokenData = Map.of("access_token", "linkedin-access-token");
            when(portabilityService.exchangeCodeForToken("code123")).thenReturn(Mono.just(tokenData));

            ResumeProfileRequest profileRequest = new ResumeProfileRequest();
            when(portabilityService.importProfile("linkedin-access-token")).thenReturn(Mono.just(profileRequest));
            when(portabilityService.storeImportResult(anyString(), eq("admin@test.com")))
                    .thenReturn(Mono.just("import-key-123"));

            webTestClient
                    .get().uri("/api/v1/resume/import/linkedin/callback?code=code123&state=valid-state")
                    .exchange()
                    .expectStatus().isFound()
                    .expectHeader().valueMatches("Location", ".*/admin/profile\\?linkedin-import=import-key-123");
        }
    }

    @Nested
    @DisplayName("GET /api/v1/resume/import/linkedin/result/{key}")
    class GetResult {

        @Test
        @DisplayName("Should return 404 when result not found")
        void shouldReturn404WhenNotFound() {
            when(portabilityService.retrieveImportResult("unknown-key", "admin@test.com"))
                    .thenReturn(Mono.empty());

            webTestClient
                    .get().uri("/api/v1/resume/import/linkedin/result/unknown-key")
                    .exchange()
                    .expectStatus().isNotFound();
        }
    }
}
