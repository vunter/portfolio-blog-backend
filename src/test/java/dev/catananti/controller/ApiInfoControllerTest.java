package dev.catananti.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ApiInfoControllerTest {

    @InjectMocks
    private ApiInfoController controller;

    private void initController() {
        ReflectionTestUtils.setField(controller, "appVersion", "2.0.0");
        ReflectionTestUtils.setField(controller, "appName", "Portfolio Blog API");
        ReflectionTestUtils.setField(controller, "appDescription", "RESTful API for Portfolio Blog");
    }

    @Nested
    @DisplayName("GET /api")
    class GetApiInfo {

        @Test
        @DisplayName("Should return API info with name and description")
        void shouldReturnApiInfo() {
            initController();

            StepVerifier.create(controller.getApiInfo())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("name")).isEqualTo("Portfolio Blog API");
                        assertThat(body.get("description")).isEqualTo("RESTful API for Portfolio Blog");
                        assertThat(body.get("documentation")).isEqualTo("/swagger-ui.html");
                        assertThat(body.get("health")).isEqualTo("/api/health");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should include versions map")
        @SuppressWarnings("unchecked")
        void shouldIncludeVersionsMap() {
            initController();

            StepVerifier.create(controller.getApiInfo())
                    .assertNext(response -> {
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        Map<String, String> versions = (Map<String, String>) body.get("versions");
                        assertThat(versions).containsEntry("v1", "/api/v1");
                        assertThat(versions).containsEntry("latest", "/api/v1");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/v1")
    class GetV1Info {

        @Test
        @DisplayName("Should return API v1 info with endpoint listing")
        @SuppressWarnings("unchecked")
        void shouldReturnV1Info() {
            initController();

            StepVerifier.create(controller.getV1Info())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("version")).isEqualTo("1.0");
                        assertThat(body.get("status")).isEqualTo("stable");

                        Map<String, String> endpoints = (Map<String, String>) body.get("endpoints");
                        assertThat(endpoints).containsEntry("articles", "/api/v1/articles");
                        assertThat(endpoints).containsEntry("tags", "/api/v1/tags");
                        assertThat(endpoints).containsEntry("rss", "/rss.xml");
                        assertThat(endpoints).containsEntry("sitemap", "/sitemap.xml");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should not expose admin endpoints in public response (F-072)")
        @SuppressWarnings("unchecked")
        void shouldNotIncludeAdminEndpoints() {
            initController();

            StepVerifier.create(controller.getV1Info())
                    .assertNext(response -> {
                        Map<String, Object> body = response.getBody();
                        // F-072: Admin paths removed from public response
                        assertThat(body).doesNotContainKey("admin");
                        // Public endpoints should still be present
                        assertThat(body).containsKey("endpoints");
                        assertThat(body).containsKey("auth");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GET /api/health")
    class HealthCheck {

        @Test
        @DisplayName("Should return health status UP")
        void shouldReturnHealthStatusUp() {
            initController();

            StepVerifier.create(controller.healthCheck())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        Map<String, Object> body = response.getBody();
                        assertThat(body).isNotNull();
                        assertThat(body.get("status")).isEqualTo("UP");
                        assertThat(body.get("version")).isEqualTo("2.0.0");
                        assertThat(body.get("timestamp")).isNotNull();
                    })
                    .verifyComplete();
        }
    }
}
