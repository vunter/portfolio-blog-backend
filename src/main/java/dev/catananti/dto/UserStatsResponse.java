package dev.catananti.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for user statistics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "User statistics response")
public class UserStatsResponse {
    
    @Schema(description = "Total number of users", example = "150")
    private long total;
    
    @Schema(description = "Number of admin users", example = "3")
    private long admins;
    
    @Schema(description = "Number of editor users", example = "25")
    private long editors;
    
    @Schema(description = "Number of dev users", example = "10")
    private long devs;
    
    @Schema(description = "Number of viewer users", example = "122")
    private long viewers;
}
