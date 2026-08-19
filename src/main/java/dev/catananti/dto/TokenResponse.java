package dev.catananti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {
    // SEG-1: WRITE_ONLY so the tokens are never serialized OUT in login/register/refresh
    // JSON bodies (HttpOnly-cookie-only design — keeps XSS from reading them). The getters
    // still work for the controllers that read them to set cookies (field annotation + @Data).
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String accessToken;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
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

    /**
     * AUD19C-MFAMETHOD: the user's preferred MFA method ("TOTP" / "EMAIL"), only set when
     * mfaRequired=true, so the FE can land on the right verification form. Deliberately
     * NOT WRITE_ONLY — unlike the tokens it must serialize in the challenge response.
     */
    private String mfaMethod;
}
