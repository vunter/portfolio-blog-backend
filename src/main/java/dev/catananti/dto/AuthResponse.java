package dev.catananti.dto;

/**
 * Token-bearing response for legacy login endpoints. Records guarantee
 * immutability so the DTO can't drift after the service returns it.
 */
public record AuthResponse(
        String token,
        String type,
        long expiresIn,
        String email,
        String name,
        String role
) {

    /** Convenience overload that defaults the token type to "Bearer". */
    public static AuthResponse bearer(String token, long expiresIn, String email, String name, String role) {
        return new AuthResponse(token, "Bearer", expiresIn, email, name, role);
    }
}
