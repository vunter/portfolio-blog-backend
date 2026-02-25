package dev.catananti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {
    private String accessToken;
    private String refreshToken;
    @Builder.Default
    private String tokenType = "Bearer";
    private long expiresIn; // seconds
    private String email;
    private String name;

    /** True when credentials are valid but MFA verification is still required. */
    @Builder.Default
    private Boolean mfaRequired = false;

    /** Temporary token used to complete the MFA challenge (only set when mfaRequired=true). */
    private String mfaToken;
}
