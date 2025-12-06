package dev.catananti.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProfileUpdateRequest(
    @Size(min = 2, max = 255, message = "Name must be between 2 and 255 characters")
    String name,

    @Email(message = "Invalid email format")
    String email,

    @Size(max = 255, message = "Username must be at most 255 characters")
    String username,

    @Size(max = 2048, message = "Avatar URL must be at most 2048 characters")
    String avatarUrl,

    @Size(max = 1000, message = "Bio must be at most 1000 characters")
    String bio,

    @Size(min = 12, max = 128, message = "Password must be between 12 and 128 characters")
    String currentPassword,

    @Size(min = 12, max = 128, message = "New password must be between 12 and 128 characters")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,}$", message = "Password must contain uppercase, lowercase, number and special character")
    String newPassword
) {}
