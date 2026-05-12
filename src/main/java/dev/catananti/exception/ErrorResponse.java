package dev.catananti.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Error response body. Designed to satisfy both the existing FE contract
 * (legacy keys: {@code timestamp}, {@code status}, {@code error},
 * {@code message}, {@code path}, {@code requestId}, {@code validationErrors})
 * and the RFC 7807 ProblemDetail shape ({@code type}, {@code title},
 * {@code status}, {@code detail}, {@code instance}). New consumers should
 * prefer the RFC 7807 keys; the legacy keys remain for backwards compatibility
 * until the frontend migrates.
 *
 * <p>Immutable record: handlers can no longer mutate validation errors on a
 * post-built instance — the constructor takes the final shape.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        String requestId,
        Map<String, String> validationErrors,
        // RFC 7807 ProblemDetail fields
        String type,
        String title,
        String detail,
        String instance
) {

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable assembler for the immutable record. */
    public static final class Builder {
        private LocalDateTime timestamp;
        private int status;
        private String error;
        private String message;
        private String path;
        private String requestId;
        private Map<String, String> validationErrors;
        private String type;
        private String title;
        private String detail;
        private String instance;

        public Builder timestamp(LocalDateTime v) { this.timestamp = v; return this; }
        public Builder status(int v) { this.status = v; return this; }
        public Builder error(String v) { this.error = v; return this; }
        public Builder message(String v) { this.message = v; return this; }
        public Builder path(String v) { this.path = v; return this; }
        public Builder requestId(String v) { this.requestId = v; return this; }
        public Builder validationErrors(Map<String, String> v) { this.validationErrors = v; return this; }
        public Builder type(String v) { this.type = v; return this; }
        public Builder title(String v) { this.title = v; return this; }
        public Builder detail(String v) { this.detail = v; return this; }
        public Builder instance(String v) { this.instance = v; return this; }

        public ErrorResponse build() {
            return new ErrorResponse(timestamp, status, error, message, path, requestId,
                    validationErrors, type, title, detail, instance);
        }
    }
}
