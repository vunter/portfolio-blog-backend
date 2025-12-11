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
    private Map<String, Object> metadata;
}
