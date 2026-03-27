package dev.catananti.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsEventRequest {

    private Long articleId;

    @NotBlank(message = "Event type is required")
    @Size(max = 50, message = "Event type must be at most 50 characters")
    @Pattern(regexp = "^[A-Z_]{1,50}$", message = "Invalid event type")
    private String eventType; // VIEW, LIKE, SHARE, CLICK, SCROLL_DEPTH

    @Size(max = 2048, message = "Referrer must be at most 2048 characters")
    private String referrer;

    @Size(max = 10, message = "Metadata must contain at most 10 entries")
    private Map<@Size(max = 50, message = "Metadata key too long") String,
                @Size(max = 500, message = "Metadata value too long") String> metadata;

    // SEC-AH-04: reCAPTCHA v3 token for bot detection
    private String recaptchaToken;

    // SEC-AH-01: Proof-of-work challenge fields
    @NotBlank(message = "Challenge ID is required")
    @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", message = "Invalid challenge ID format")
    private String challengeId;

    @NotBlank(message = "Solution is required")
    @Size(max = 20, message = "Solution too long")
    @Pattern(regexp = "^\\d+$", message = "Solution must be numeric")
    private String solution;
}
