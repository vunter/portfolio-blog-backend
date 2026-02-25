package dev.catananti.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to verify MFA during login flow.
 * The mfaToken is the temporary token issued after successful credential verification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaLoginVerifyRequest {
    @NotBlank(message = "MFA token is required")
    private String mfaToken;

    @NotBlank(message = "Code is required")
    private String code;

    @NotBlank(message = "Method is required")
    private String method;
}
