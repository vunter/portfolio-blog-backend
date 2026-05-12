package dev.catananti.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Standard response DTO for simple messages. Immutable record so callers
 * can't accidentally mutate a shared instance.
 */
@Schema(description = "Standard message response")
public record MessageResponse(
        @Schema(description = "Response message", example = "Operation completed successfully")
        String message,

        @Schema(description = "Timestamp of the response")
        Instant timestamp,

        @Schema(description = "Whether the operation was successful", example = "true")
        boolean success
) {

    /** Create a simple success response. */
    public static MessageResponse of(String message) {
        return new MessageResponse(message, Instant.now(), true);
    }

    /** Create an error response. */
    public static MessageResponse error(String message) {
        return new MessageResponse(message, Instant.now(), false);
    }
}
