package dev.catananti.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Response DTO for resume template data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResumeTemplateResponse {

    private String id;
    private String slug;
    private String alias;
    private String name;
    private String description;
    private Map<String, String> names;
    private Map<String, String> descriptions;
    private String htmlContent;
    private String cssContent;
    private String status;
    private String ownerId;
    private String ownerName;
    private Integer version;
    private Boolean isDefault;
    private String paperSize;
    private String orientation;
    private Integer downloadCount;
    private String previewUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
