package dev.catananti.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleUpdateRequest {

    @NotBlank(message = "Role is required")
    @Pattern(regexp = "ADMIN|DEV|EDITOR|VIEWER", message = "Role must be ADMIN, DEV, EDITOR, or VIEWER")
    private String role;
}
