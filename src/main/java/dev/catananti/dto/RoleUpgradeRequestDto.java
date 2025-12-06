package dev.catananti.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO for submitting a role upgrade request.
 */
public record RoleUpgradeRequestDto(
    @NotBlank(message = "Requested role is required")
    @Pattern(regexp = "DEV|EDITOR", message = "Can only request DEV or EDITOR role")
    String requestedRole,

    @Size(max = 1000, message = "Reason must be at most 1000 characters")
    String reason
) {}
