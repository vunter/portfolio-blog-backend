package dev.catananti.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q12.1: Unit tests for ClientErrorController.
 */
@ExtendWith(MockitoExtension.class)
class ClientErrorControllerTest {

    private final ClientErrorController controller = new ClientErrorController();

    @Nested
    @DisplayName("POST /api/v1/client-errors")
    class ReportError {

        @Test
        @DisplayName("should accept valid error report and return 204")
        void shouldAcceptValidReport() {
            var report = new ClientErrorController.ClientErrorReport(
                    "TypeError: Cannot read properties of null",
                    "https://catananti.dev/blog/test",
                    "angular-global-error-handler",
                    "TypeError: Cannot read properties of null\n    at ArticleComponent.ngOnInit",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            );

            StepVerifier.create(controller.reportError(report))
                    .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(204))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should accept report with minimal fields")
        void shouldAcceptMinimalReport() {
            var report = new ClientErrorController.ClientErrorReport(
                    "Unexpected error",
                    null,
                    null,
                    null,
                    null
            );

            StepVerifier.create(controller.reportError(report))
                    .assertNext(response -> assertThat(response.getStatusCode().value()).isEqualTo(204))
                    .verifyComplete();
        }
    }
}
