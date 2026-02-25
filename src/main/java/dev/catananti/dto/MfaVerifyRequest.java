package dev.catananti.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaVerifyRequest {
    @NotBlank(message = "Code is required")
    private String code;

    @NotBlank(message = "Method is required")
    @Pattern(regexp = "TOTP|EMAIL", message = "Method must be TOTP or EMAIL")
    private String method;
}
