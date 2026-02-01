package dev.catananti.exception;

import dev.catananti.config.LocaleConstants;
import dev.catananti.service.RecaptchaService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    @Mock
    private MessageSource messageSource;

    @Mock
    private ServerWebExchange exchange;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private HttpHeaders headers;

    @Mock
    private RequestPath requestPath;

    @InjectMocks
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(exchange.getRequest()).thenReturn(request);
        lenient().when(request.getHeaders()).thenReturn(headers);
        lenient().when(request.getPath()).thenReturn(requestPath);
        lenient().when(requestPath.value()).thenReturn("/api/test");
        lenient().when(headers.getFirst(HttpHeaders.ACCEPT_LANGUAGE)).thenReturn(null);
        // Default: return the key itself (acts as passthrough)
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ──────────────────────────────────────────────
    // handleResourceNotFound
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleResourceNotFound()")
    class HandleResourceNotFound {

        @Test
        @DisplayName("should return 404 with message and path")
        void shouldReturn404() {
            var ex = new ResourceNotFoundException("Article not found");

            StepVerifier.create(handler.handleResourceNotFound(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(404);
                        assertThat(resp.getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleAccountLocked
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleAccountLocked()")
    class HandleAccountLocked {

        @Test
        @DisplayName("should return 429 (Too Many Requests) with remaining minutes")
        void shouldReturn429() {
            var ex = new AccountLockedException(15);

            StepVerifier.create(handler.handleAccountLocked(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
                        assertThat(resp.getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleBadCredentials
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleBadCredentials()")
    class HandleBadCredentials {

        @Test
        @DisplayName("should return 401 without exposing internal details")
        void shouldReturn401() {
            var ex = new BadCredentialsException("Invalid password (attempt 3/5)");

            StepVerifier.create(handler.handleBadCredentials(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(401);
                        assertThat(resp.getMessage()).doesNotContain("attempt");
                        assertThat(resp.getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleValidationErrors
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleValidationErrors()")
    class HandleValidationErrors {

        @Test
        @DisplayName("should return 400 with field-level validation errors")
        void shouldReturn400WithFieldErrors() {
            WebExchangeBindException ex = mock(WebExchangeBindException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError fieldError = new FieldError("article", "title", "must not be blank");

            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

            StepVerifier.create(handler.handleValidationErrors(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(400);
                        assertThat(resp.getValidationErrors()).containsEntry("title", "must not be blank");
                        assertThat(resp.getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should use default message when field error message is null")
        void shouldUseDefaultMessageWhenNull() {
            WebExchangeBindException ex = mock(WebExchangeBindException.class);
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError fieldError = new FieldError("article", "title", null);

            when(ex.getBindingResult()).thenReturn(bindingResult);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

            StepVerifier.create(handler.handleValidationErrors(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(400);
                        assertThat(resp.getValidationErrors()).containsKey("title");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleRecaptchaException
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleRecaptchaException()")
    class HandleRecaptchaException {

        @Test
        @DisplayName("should return 400 for reCAPTCHA failure")
        void shouldReturn400() {
            var ex = new RecaptchaService.RecaptchaException("reCAPTCHA verification failed");

            StepVerifier.create(handler.handleRecaptchaException(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(400);
                        assertThat(resp.getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleDuplicateResource
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleDuplicateResource()")
    class HandleDuplicateResource {

        @Test
        @DisplayName("should return 409 for duplicate")
        void shouldReturn409() {
            var ex = new DuplicateResourceException("Tag already exists with name: 'java'");

            StepVerifier.create(handler.handleDuplicateResource(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(409);
                        assertThat(resp.getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleSecurityException
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleSecurityException()")
    class HandleSecurityException {

        @Test
        @DisplayName("should return 401 for security exception")
        void shouldReturn401() {
            var ex = new SecurityException("Access denied");

            StepVerifier.create(handler.handleSecurityException(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(401);
                        assertThat(resp.getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleIllegalArgument
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleIllegalArgument()")
    class HandleIllegalArgument {

        @Test
        @DisplayName("should return 400 with sanitized message when not an i18n key")
        void shouldReturn400WithSanitizedMessage() {
            var ex = new IllegalArgumentException("Invalid sort parameter");

            StepVerifier.create(handler.handleIllegalArgument(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(400);
                        assertThat(resp.getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return translated message when exception message is an i18n key")
        void shouldReturnTranslatedMessageForI18nKey() {
            // Override passthrough: return a different string when key resolves
            when(messageSource.getMessage(eq("error.invalid_locale"), any(), eq("error.invalid_locale"), any(Locale.class)))
                    .thenReturn("Locale is not supported");

            var ex = new IllegalArgumentException("error.invalid_locale");

            StepVerifier.create(handler.handleIllegalArgument(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(400);
                        assertThat(resp.getMessage()).isEqualTo("Locale is not supported");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleConstraintViolation
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleConstraintViolation()")
    class HandleConstraintViolation {

        @Test
        @DisplayName("should return 400 with extracted field names")
        void shouldReturn400WithFieldConstraints() {
            ConstraintViolation<?> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(violation.getPropertyPath()).thenReturn(path);
            when(path.toString()).thenReturn("createArticle.title");
            when(violation.getMessage()).thenReturn("must not be blank");

            ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

            StepVerifier.create(handler.handleConstraintViolation(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(400);
                        assertThat(resp.getValidationErrors()).containsEntry("title", "must not be blank");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should use full path when no dot separator")
        void shouldUseFullPathWhenNoDot() {
            ConstraintViolation<?> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(violation.getPropertyPath()).thenReturn(path);
            when(path.toString()).thenReturn("email");
            when(violation.getMessage()).thenReturn("invalid format");

            ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

            StepVerifier.create(handler.handleConstraintViolation(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getValidationErrors()).containsEntry("email", "invalid format");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleResponseStatusException
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleResponseStatusException()")
    class HandleResponseStatusException {

        @Test
        @DisplayName("should return matching HTTP status code from the exception")
        void shouldReturnMatchingStatusCode() {
            var ex = new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");

            StepVerifier.create(handler.handleResponseStatusException(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatusCode().value()).isEqualTo(403);
                        assertThat(resp.getBody()).isNotNull();
                        assertThat(resp.getBody().getStatus()).isEqualTo(403);
                        assertThat(resp.getBody().getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return 404 status for NOT_FOUND exception")
        void shouldReturn404ForNotFound() {
            var ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");

            StepVerifier.create(handler.handleResponseStatusException(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatusCode().value()).isEqualTo(404);
                        assertThat(resp.getBody().getStatus()).isEqualTo(404);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should use error key as message when reason is null")
        void shouldUseErrorKeyWhenReasonIsNull() {
            var ex = new ResponseStatusException(HttpStatus.BAD_REQUEST);

            StepVerifier.create(handler.handleResponseStatusException(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatusCode().value()).isEqualTo(400);
                        assertThat(resp.getBody()).isNotNull();
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleRuntimeException
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleRuntimeException()")
    class HandleRuntimeException {

        @Test
        @DisplayName("should return 500 without leaking internal details")
        void shouldReturn500() {
            var ex = new RuntimeException("NullPointerException at com.foo.Bar");

            StepVerifier.create(handler.handleRuntimeException(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(500);
                        assertThat(resp.getMessage()).doesNotContain("NullPointerException");
                        assertThat(resp.getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // handleGenericException
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("handleGenericException()")
    class HandleGenericException {

        @Test
        @DisplayName("should return 500 for any unhandled exception")
        void shouldReturn500() {
            var ex = new Exception("Something completely unexpected");

            StepVerifier.create(handler.handleGenericException(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getStatus()).isEqualTo(500);
                        assertThat(resp.getPath()).isEqualTo("/api/test");
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // resolveLocale (private — tested indirectly)
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("resolveLocale()")
    class ResolveLocale {

        @Test
        @DisplayName("should fall back to English when exchange has null Accept-Language")
        void nullAcceptLanguage_shouldDefaultToEnglish() {
            when(headers.getFirst(HttpHeaders.ACCEPT_LANGUAGE)).thenReturn(null);

            var ex = new ResourceNotFoundException("test");

            // The locale is used internally; we verify the msg() calls use ENGLISH
            StepVerifier.create(handler.handleResourceNotFound(ex, exchange))
                    .assertNext(resp -> {
                        // Verify MessageSource was invoked with English locale
                        verify(messageSource, atLeastOnce())
                                .getMessage(anyString(), any(), anyString(), eq(Locale.ENGLISH));
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should resolve supported locale from Accept-Language header")
        void supportedLocale_shouldBeUsed() {
            when(headers.getFirst(HttpHeaders.ACCEPT_LANGUAGE)).thenReturn("pt-BR");

            var ex = new ResourceNotFoundException("test");

            StepVerifier.create(handler.handleResourceNotFound(ex, exchange))
                    .assertNext(resp -> {
                        verify(messageSource, atLeastOnce())
                                .getMessage(anyString(), any(), anyString(), eq(Locale.of("pt", "BR")));
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should fall back to English for unsupported locale")
        void unsupportedLocale_shouldFallBackToEnglish() {
            when(headers.getFirst(HttpHeaders.ACCEPT_LANGUAGE)).thenReturn("ko-KR");

            var ex = new ResourceNotFoundException("test");

            StepVerifier.create(handler.handleResourceNotFound(ex, exchange))
                    .assertNext(resp -> {
                        verify(messageSource, atLeastOnce())
                                .getMessage(anyString(), any(), anyString(), eq(Locale.ENGLISH));
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should parse complex Accept-Language header with quality values")
        void complexAcceptLanguage_shouldParseProperly() {
            when(headers.getFirst(HttpHeaders.ACCEPT_LANGUAGE)).thenReturn("fr;q=0.9, en;q=0.8");

            var ex = new ResourceNotFoundException("test");

            StepVerifier.create(handler.handleResourceNotFound(ex, exchange))
                    .assertNext(resp -> {
                        // French is supported and has highest q, so should be used
                        verify(messageSource, atLeastOnce())
                                .getMessage(anyString(), any(), anyString(), eq(Locale.of("fr")));
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────
    // sanitizeErrorMessage (private — tested via handleIllegalArgument)
    // ──────────────────────────────────────────────
    @Nested
    @DisplayName("sanitizeErrorMessage()")
    class SanitizeErrorMessage {

        @Test
        @DisplayName("should strip Windows file paths from message")
        void shouldStripWindowsFilePaths() {
            var ex = new IllegalArgumentException("Error at C:\\Users\\dev\\project\\Main.java line 42");

            StepVerifier.create(handler.handleIllegalArgument(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getMessage()).doesNotContain("C:\\Users");
                        assertThat(resp.getMessage()).contains("[path]");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should strip Unix file paths from message")
        void shouldStripUnixFilePaths() {
            var ex = new IllegalArgumentException("Error at /home/dev/project/Main.java");

            StepVerifier.create(handler.handleIllegalArgument(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getMessage()).doesNotContain("/home/dev");
                        assertThat(resp.getMessage()).contains("[path]");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should strip SQL keywords from message")
        void shouldStripSqlKeywords() {
            var ex = new IllegalArgumentException("SELECT * FROM users WHERE id = 1");

            StepVerifier.create(handler.handleIllegalArgument(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getMessage()).doesNotContain("SELECT");
                        assertThat(resp.getMessage()).doesNotContain("FROM");
                        assertThat(resp.getMessage()).doesNotContain("WHERE");
                        assertThat(resp.getMessage()).contains("[query]");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should strip Java class names from message")
        void shouldStripJavaClassNames() {
            var ex = new IllegalArgumentException("caused by org.hibernate.QueryException in module");

            StepVerifier.create(handler.handleIllegalArgument(ex, exchange))
                    .assertNext(resp -> {
                        assertThat(resp.getMessage()).doesNotContain("org.hibernate.QueryException");
                        assertThat(resp.getMessage()).contains("[class]");
                    })
                    .verifyComplete();
        }
    }
}
