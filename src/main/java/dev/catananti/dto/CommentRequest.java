package dev.catananti.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentRequest {

    @NotBlank(message = "Author name is required")
    @Size(min = 2, max = 100, message = "Author name must be between 2 and 100 characters")
    private String authorName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String authorEmail;

    @NotBlank(message = "Content is required")
    @Size(min = 3, max = 2000, message = "Content must be between 3 and 2000 characters")
    private String content;

    private Long parentId; // For replies

    private String recaptchaToken;

    /**
     * Structural authorship link. Never bound from the request body — the
     * controller overwrites it from the authenticated principal, exactly like
     * authorName/authorEmail above.
     */
    private Long userId;

    /** Q7.10: Normalize email to lowercase to prevent case-sensitive duplicates */
    public void setAuthorEmail(String authorEmail) {
        this.authorEmail = authorEmail != null ? authorEmail.toLowerCase().trim() : null;
    }
}
