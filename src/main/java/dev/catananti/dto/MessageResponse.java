package dev.catananti.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Standard response DTO for simple messages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Standard message response")
public class MessageResponse {
    
    @Schema(description = "Response message", example = "Operation completed successfully")
    private String message;
    
    @Schema(description = "Timestamp of the response")
    @Builder.Default
    private Instant timestamp = Instant.now();
    
    @Schema(description = "Whether the operation was successful", example = "true")
    @Builder.Default
    private boolean success = true;
    
    /**
     * Create a simple success message response.
     */
    public static MessageResponse of(String message) {
        return MessageResponse.builder()
                .message(message)
                .success(true)
                .timestamp(Instant.now())
                .build();
    }
    
    /**
     * Create an error message response.
     */
    public static MessageResponse error(String message) {
        return MessageResponse.builder()
                .message(message)
                .success(false)
                .timestamp(Instant.now())
                .build();
    }
}
