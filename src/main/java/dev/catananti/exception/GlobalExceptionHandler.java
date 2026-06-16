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
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Pattern FILE_PATH_WIN = Pattern.compile("[A-Za-z]:\\\\[^\\s]+");
    private static final Pattern FILE_PATH_UNIX = Pattern.compile("/[a-zA-Z0-9_/.-]+\\.(java|class|jar)");
    private static final Pattern PACKAGE_REF = Pattern.compile("([a-z]+\\.)+[A-Z][a-zA-Z0-9]+");
    private static final Pattern SQL_KEYWORDS = Pattern.compile("(?i)(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|JOIN)\\s+");

    private final MessageSource messageSource;

    /**
     * Build a standardized error response. Populates both the legacy keys
     * (status / error / message / path) and the RFC 7807 keys (title /
     * detail / instance) so clients can opt into the standard shape without
     * the server breaking the existing one.
     */
    private ErrorResponse buildErrorResponse(HttpStatus status, String error, String message,
                                              ServerWebExchange exchange) {
        return buildErrorResponse(status, error, message, exchange, null);
    }

    private ErrorResponse buildErrorResponse(HttpStatus status, String error, String message,
                                              ServerWebExchange exchange,
                                              Map<String, String> validationErrors) {
        String requestId = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        String path = exchange.getRequest().getPath().value();
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(error)
                .message(message)
                .path(path)
                .requestId(requestId)
                .validationErrors(validationErrors)
                // RFC 7807 surface — clients should prefer these going forward.
                .type("about:blank")
                .title(error)
                .detail(message)
                .instance(path)
                .build();
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Mono<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.warn("status=404 path={} Resource not found: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(buildErrorResponse(HttpStatus.NOT_FOUND,
                msg(locale, "error.not_found"), msg(locale, ex.getMessage()), exchange));
    }

    @ExceptionHandler(AccountLockedException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Mono<ErrorResponse> handleAccountLocked(AccountLockedException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.warn("status=429 path={} Account locked: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(buildErrorResponse(HttpStatus.TOO_MANY_REQUESTS,
                msg(locale, "error.unauthorized"),
                msg(locale, "error.account_locked", ex.getRemainingMinutes()), exchange));
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Mono<ErrorResponse> handleBadCredentials(BadCredentialsException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.warn("status=401 path={} Authentication failed: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        // SEC: Never expose attempt count details to the client
        return Mono.just(buildErrorResponse(HttpStatus.UNAUTHORIZED,
                msg(locale, "error.unauthorized"), msg(locale, "error.invalid_credentials"), exchange));
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
        String path = exchange.getRequest().getPath().value();
        log.warn("status=400 path={} Validation failed: {}", path, errors, ex);
        return Mono.just(buildErrorResponse(HttpStatus.BAD_REQUEST,
                msg(locale, "error.validation_failed"), msg(locale, "error.invalid_request_data"),
                exchange, errors));
    }

    @ExceptionHandler(dev.catananti.service.RecaptchaService.RecaptchaException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ErrorResponse> handleRecaptchaException(
            dev.catananti.service.RecaptchaService.RecaptchaException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.warn("status=400 path={} reCAPTCHA verification failed: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(buildErrorResponse(HttpStatus.BAD_REQUEST,
                msg(locale, "error.recaptcha_failed"), msg(locale, "error.recaptcha_retry"), exchange));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Mono<ErrorResponse> handleDuplicateResource(DuplicateResourceException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.warn("status=409 path={} Duplicate resource: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(buildErrorResponse(HttpStatus.CONFLICT,
                msg(locale, "error.conflict"), msg(locale, ex.getMessage()), exchange));
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Mono<ErrorResponse> handleSecurityException(SecurityException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.warn("status=401 path={} Security exception: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(buildErrorResponse(HttpStatus.UNAUTHORIZED,
                msg(locale, "error.unauthorized"), msg(locale, "error.unauthorized"), exchange));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.warn("status=400 path={} Invalid argument: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        // Try to resolve as i18n key first; fall back to sanitized message
        String translated = msg(locale, ex.getMessage());
        String safeMessage = translated.equals(ex.getMessage())
                ? sanitizeErrorMessage(ex.getMessage(), locale)
                : translated;
        return Mono.just(buildErrorResponse(HttpStatus.BAD_REQUEST,
                msg(locale, "error.bad_request"), safeMessage, exchange));
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
        String path = exchange.getRequest().getPath().value();
        log.warn("status=400 path={} Constraint violations: {}", path, errors, ex);
        return Mono.just(buildErrorResponse(HttpStatus.BAD_REQUEST,
                msg(locale, "error.validation_failed"), msg(locale, "error.invalid_request_params"),
                exchange, errors));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleResponseStatusException(
            ResponseStatusException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        int statusValue = ex.getStatusCode().value();
        // 406 NOT_ACCEPTABLE is common for SSE content negotiation — don't spam logs
        if (statusValue == 406) {
            log.trace("status=406 path={} SSE content negotiation rejected: {}", path, ex.getReason());
        } else if (statusValue >= 500) {
            log.error("status={} path={} Response status exception: {}", statusValue, path, ex.getReason(), ex);
        } else {
            log.warn("status={} path={} Response status exception: {}", statusValue, path, ex.getReason(), ex);
        }
        Locale locale = resolveLocale(exchange);
        HttpStatusCode statusCode = ex.getStatusCode();
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String errorKey = statusToKey(status);
        String reason = ex.getReason();
        String translatedMessage = reason != null ? msg(locale, reason) : msg(locale, errorKey);
        return Mono.just(ResponseEntity.status(status).body(
                buildErrorResponse(status, msg(locale, errorKey), translatedMessage, exchange)));
    }

    @ExceptionHandler(PdfGenerationException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ErrorResponse> handlePdfGenerationException(PdfGenerationException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.error("status=500 path={} PDF generation failed: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                msg(locale, "error.internal_server_error"), msg(locale, "error.pdf_generation_failed"), exchange));
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Mono<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.warn("status=403 path={} Access denied: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(buildErrorResponse(HttpStatus.FORBIDDEN,
                msg(locale, "error.forbidden"), msg(locale, "error.forbidden"), exchange));
    }

    @ExceptionHandler(ServerWebInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<ErrorResponse> handleServerWebInputException(ServerWebInputException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.warn("status=400 path={} Bad request input: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        // PII-3: ex.getReason() can contain raw framework detail (parameter names, types, decoder internals).
        // Mirror the IllegalArgumentException handler: resolve as an i18n key first, otherwise sanitize before
        // exposing it to the client — never return the raw reason verbatim.
        String reason = ex.getReason();
        String safeMessage;
        if (reason == null) {
            safeMessage = msg(locale, "error.invalid_request");
        } else {
            String translated = msg(locale, reason);
            safeMessage = translated.equals(reason) ? sanitizeErrorMessage(reason, locale) : translated;
        }
        return Mono.just(buildErrorResponse(HttpStatus.BAD_REQUEST,
                msg(locale, "error.bad_request"), safeMessage, exchange));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Mono<ErrorResponse> handleDuplicateKeyException(DuplicateKeyException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.warn("status=409 path={} Database constraint violation: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(buildErrorResponse(HttpStatus.CONFLICT,
                msg(locale, "error.duplicate_resource"), msg(locale, "error.resource_already_exists"), exchange));
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ErrorResponse> handleRuntimeException(RuntimeException ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        if (isClientDisconnect(ex)) {
            log.trace("status=499 path={} Client disconnected: {}", path, ex.getMessage());
            return Mono.just(buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Client disconnected", "Client closed the connection before response completed", exchange));
        }
        log.error("status=500 path={} Runtime exception: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                msg(locale, "error.internal_server_error"), msg(locale, "error.unexpected_error"), exchange));
    }

    /**
     * Client-disconnect detection via exception type rather than message text.
     * Netty publishes {@link reactor.netty.channel.AbortedException} when a
     * downstream consumer goes away mid-response; matching by class keeps us
     * insulated from Netty / Reactor message-string churn across versions.
     */
    private boolean isClientDisconnect(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            String name = cur.getClass().getName();
            if (name.equals("reactor.netty.channel.AbortedException")
                    || name.equals("java.io.IOException") && cur.getMessage() != null
                            && (cur.getMessage().contains("Broken pipe")
                                || cur.getMessage().contains("Connection reset by peer"))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Mono<ErrorResponse> handleGenericException(Exception ex, ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        log.error("status=500 path={} Unexpected error: {}", path, ex.getMessage(), ex);
        Locale locale = resolveLocale(exchange);
        return Mono.just(buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                msg(locale, "error.internal_server_error"), msg(locale, "error.unexpected_error"), exchange));
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
            case FORBIDDEN -> "error.forbidden";
            case CONFLICT -> "error.conflict";
            case BAD_REQUEST -> "error.bad_request";
            case TOO_MANY_REQUESTS -> "error.rate_limit_exceeded";
            default -> "error.internal_server_error";
        };
    }
}
