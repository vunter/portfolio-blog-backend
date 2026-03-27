package dev.catananti.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsChallengeResponse {
    private String challengeId;
    private String nonce;
    private int difficulty;
    private Instant expiresAt;
}
