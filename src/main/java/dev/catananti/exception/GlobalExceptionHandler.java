package dev.catananti.exception;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import dev.catananti.config.LocaleConstants;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// TODO F-300: Extract common error response builder method to reduce duplication across handlers
// TODO F-304: Add request-id (correlation ID) to all ErrorResponse instances for traceability
@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Pattern FILE_PATH_WIN = Pattern.compile("[A-Za-z]:\\\\[^\\s]+");
    private static final Pattern FILE_PATH_UNIX = Pattern.compile("/[a-zA-Z0-9_/.-]+\\.(java|class|jar)");
    private static final Pattern PACKAGE_REF = Pattern.compile("([a-z]+\\.)+[A-Z][a-zA-Z0-9]+");
    private static final Pattern SQL_KEYWORDS = Pattern.compile("(?i)(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|JOIN)\\s+");

    private final MessageSource messageSource;

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Mono<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex, ServerWebExchange exchange) {
        log.warn("Resource not found: {}", ex.getMessage());
        Locale locale = resolveLocale(exchange);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error(msg(locale, "error.not_found"))
                .message(msg(locale, ex.getMessage()))
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(AccountLockedException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Mono<ErrorResponse> handleAccountLocked(AccountLockedException ex, ServerWebExchange exchange) {
        log.warn("Account locked: {}", ex.getMessage());
        Locale locale = resolveLocale(exchange);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .error(msg(locale, "error.unauthorized"))
                .message(msg(locale, "error.account_locked", ex.getRemainingMinutes()))
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Mono<ErrorResponse> handleBadCredentials(BadCredentialsException ex, ServerWebExchange exchange) {
        log.warn("Authentication failed: {}", ex.getMessage());
        Locale locale = resolveLocale(exchange);
        // SEC: Never expose attempt count details to the client
        String responseMessage = msg(locale, "error.invalid_credentials");
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error(msg(locale, "error.unauthorized"))
                .message(responseMessage)
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ErrorResponse> handleValidationErrors(WebExchangeBindException ex, ServerWebExchange exchange) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toUnmodifiableMap(
                        FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : msg(resolveLocale(exchange), "error.invalid_value"),
                        (existing, _) -> existing
                ));

        Locale locale = resolveLocale(exchange);
        log.warn("Validation failed: {}", errors);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(msg(locale, "error.validation_failed"))
                .message(msg(locale, "error.invalid_request_data"))
                .path(exchange.getRequest().getPath().value())
                .validationErrors(errors)
                .build());
    }

    @ExceptionHandler(dev.catananti.service.RecaptchaService.RecaptchaException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ErrorResponse> handleRecaptchaException(
            dev.catananti.service.RecaptchaService.RecaptchaException ex, ServerWebExchange exchange) {
        log.warn("reCAPTCHA verification failed: {}", ex.getMessage());
        Locale locale = resolveLocale(exchange);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(msg(locale, "error.recaptcha_failed"))
                .message(msg(locale, "error.recaptcha_retry"))
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Mono<ErrorResponse> handleDuplicateResource(DuplicateResourceException ex, ServerWebExchange exchange) {
        log.warn("Duplicate resource: {}", ex.getMessage());
        Locale locale = resolveLocale(exchange);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(msg(locale, "error.conflict"))
                .message(msg(locale, ex.getMessage()))
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Mono<ErrorResponse> handleSecurityException(SecurityException ex, ServerWebExchange exchange) {
        log.warn("Security exception: {}", ex.getMessage());
        Locale locale = resolveLocale(exchange);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error(msg(locale, "error.unauthorized"))
                .message(msg(locale, "error.unauthorized"))
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, ServerWebExchange exchange) {
        log.warn("Invalid argument: {}", ex.getMessage());
        Locale locale = resolveLocale(exchange);
        // Try to resolve as i18n key first; fall back to sanitized message
        String translated = msg(locale, ex.getMessage());
        String safeMessage = translated.equals(ex.getMessage())
                ? sanitizeErrorMessage(ex.getMessage(), locale)
                : translated;
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(msg(locale, "error.bad_request"))
                .message(safeMessage)
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ErrorResponse> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex, ServerWebExchange exchange) {
        Map<String, String> errors = new HashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            errors.put(field, violation.getMessage());
        });

        Locale locale = resolveLocale(exchange);
        log.warn("Constraint violations: {}", errors);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(msg(locale, "error.validation_failed"))
                .message(msg(locale, "error.invalid_request_params"))
                .path(exchange.getRequest().getPath().value())
                .validationErrors(errors)
                .build());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleResponseStatusException(
            ResponseStatusException ex, ServerWebExchange exchange) {
        log.warn("Response status exception: {} - {}", ex.getStatusCode(), ex.getReason());
        Locale locale = resolveLocale(exchange);
        HttpStatusCode statusCode = ex.getStatusCode();
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String errorKey = statusToKey(status);
        String reason = ex.getReason();
        String translatedMessage = reason != null ? msg(locale, reason) : msg(locale, errorKey);
        return Mono.just(ResponseEntity.status(status).body(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(msg(locale, errorKey))
                .message(translatedMessage)
                .path(exchange.getRequest().getPath().value())
                .build()));
    }

    @ExceptionHandler(PdfGenerationException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ErrorResponse> handlePdfGenerationException(PdfGenerationException ex, ServerWebExchange exchange) {
        log.error("PDF generation failed: {}", ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(msg(locale, "error.internal_server_error"))
                .message(msg(locale, "error.pdf_generation_failed"))
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Mono<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex, ServerWebExchange exchange) {
        log.warn("Access denied: {}", ex.getMessage());
        Locale locale = resolveLocale(exchange);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error(msg(locale, "error.forbidden"))
                .message("Access denied")
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(ServerWebInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ErrorResponse> handleServerWebInputException(ServerWebInputException ex, ServerWebExchange exchange) {
        log.warn("Bad request input: {}", ex.getMessage());
        Locale locale = resolveLocale(exchange);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(msg(locale, "error.bad_request"))
                .message(ex.getReason() != null ? ex.getReason() : msg(locale, "error.invalid_request"))
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ErrorResponse> handleRuntimeException(RuntimeException ex, ServerWebExchange exchange) {
        log.error("Runtime exception: {}", ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(msg(locale, "error.internal_server_error"))
                .message(msg(locale, "error.unexpected_error"))
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ErrorResponse> handleGenericException(Exception ex, ServerWebExchange exchange) {
        log.error("Unexpected error: ", ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(msg(locale, "error.internal_server_error"))
                .message(msg(locale, "error.unexpected_error"))
                .path(exchange.getRequest().getPath().value())
                .build());
    }

    /**
     * Resolve locale from the Accept-Language header.
     */
    private Locale resolveLocale(ServerWebExchange exchange) {
        String acceptLanguage = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE);
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            try {
                List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
                Locale matched = Locale.lookup(ranges, LocaleConstants.SUPPORTED_LOCALES);
                if (matched != null) {
                    return matched;
                }
            } catch (IllegalArgumentException e) {
                // Malformed Accept-Language header, fall through to default
            }
        }
        return Locale.ENGLISH;
    }

    /**
     * Get a translated message from MessageSource.
     */
    private String msg(Locale locale, String code, Object... args) {
        return messageSource.getMessage(code, args, code, locale);
    }

    /**
     * Sanitize error messages to prevent leaking internal details.
     */
    private String sanitizeErrorMessage(String message, Locale locale) {
        if (message == null || message.isBlank()) {
            return msg(locale, "error.invalid_request");
        }

        // Remove file paths
        String sanitized = FILE_PATH_WIN.matcher(message).replaceAll("[path]");
        sanitized = FILE_PATH_UNIX.matcher(sanitized).replaceAll("[path]");

        // Remove package/class references
        sanitized = PACKAGE_REF.matcher(sanitized).replaceAll("[class]");

        // Remove SQL-like content
        sanitized = SQL_KEYWORDS.matcher(sanitized).replaceAll("[query] ");

        // Truncate long messages
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200) + "...";
        }

        return sanitized;
    }

    /**
     * Map HTTP status to i18n error key.
     */
    private String statusToKey(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "error.not_found";
            case UNAUTHORIZED -> "error.unauthorized";
            case FORBIDDEN -> "error.unauthorized";
            case CONFLICT -> "error.conflict";
            case BAD_REQUEST -> "error.bad_request";
            case TOO_MANY_REQUESTS -> "error.rate_limit_exceeded";
            default -> "error.internal_server_error";
        };
    }
}
