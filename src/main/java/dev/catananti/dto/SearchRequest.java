package dev.catananti.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {
    @Size(max = 200, message = "Query must be at most 200 characters")
    private String query;
    
    @Size(max = 10, message = "Maximum of 10 tags allowed")
    private List<@Pattern(regexp = "^[a-z0-9-]+$", message = "Invalid tag format") String> tags;
    
    @Pattern(regexp = "^(relevance|date|views|likes)$", message = "Invalid sort field")
    @Builder.Default
    private String sortBy = "relevance";
    
    @Pattern(regexp = "^(asc|desc)$", message = "Invalid sort order")
    @Builder.Default
    private String sortOrder = "desc";
    
    @Min(value = 0, message = "Page must be at least 0")
    @Builder.Default
    private int page = 0;
    
    @Min(value = 1, message = "Size must be at least 1")
    @Max(value = 50, message = "Size must be at most 50")
    @Builder.Default
    private int size = 10;

    @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$", message = "Invalid locale format")
    private String locale;

    private LocalDate dateFrom;
    private LocalDate dateTo;
}
