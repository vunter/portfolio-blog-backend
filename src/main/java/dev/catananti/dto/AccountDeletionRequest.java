package dev.catananti.dto;

/**
 * Body of DELETE /api/v1/account. {@code password} reauthenticates the holder;
 * {@code mode} is DEACTIVATE (reversible) or ERASE (LGPD art. 18, VI);
 * {@code cancelNewsletter} is the explicit choice about the linked subscription.
 */
public record AccountDeletionRequest(
        String password,
        String mode,
        boolean cancelNewsletter) {
}
