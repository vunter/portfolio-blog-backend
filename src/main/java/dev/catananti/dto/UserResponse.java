package dev.catananti.dto;

import dev.catananti.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private String id;
    private String name;
    private String username;
    private String email;
    private String avatarUrl;
    private String bio;
    private String role;
    private Boolean active;
    // AUD19: read-through of User.emailVerified so the account page can offer
    // "resend verification" only when the address is still unverified.
    private Boolean emailVerified;
    private Boolean hasPassword;
    private Boolean termsAccepted;
    private String preferredLocale;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserResponse fromEntity(User user) {
        return UserResponse.builder()
                .id(String.valueOf(user.getId()))
                .name(user.getName())
                .username(user.getUsername())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .bio(user.getBio())
                .role(user.getRole())
                .active(user.getActive())
                .emailVerified(user.getEmailVerified())
                .hasPassword(user.getPasswordHash() != null && !user.getPasswordHash().isEmpty())
                .termsAccepted(user.getTermsAccepted())
                .preferredLocale(user.getPreferredLocale())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
