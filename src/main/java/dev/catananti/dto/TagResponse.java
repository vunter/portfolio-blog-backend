package dev.catananti.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TagResponse {
    private String id;
    private String name;
    private String slug;
    private String description;
    private String color;
    private Map<String, String> names;
    private Map<String, String> descriptions;
    @Builder.Default
    private Integer articleCount = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
