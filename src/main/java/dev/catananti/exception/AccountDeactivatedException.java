package dev.catananti.exception;

/**
 * AUD19C-DEACT: thrown when valid credentials hit a deactivated account.
 *
 * <p>Previously this case threw {@link org.springframework.security.authentication.BadCredentialsException},
 * which {@code GlobalExceptionHandler} masks as a generic "invalid credentials" 401 — telling the
 * holder their password was wrong when it wasn't. The MFA-completion and refresh paths already
 * reveal deactivation ({@code error.account_deactivated}); this exception makes the password-login
 * path consistent: it maps to 403 with the {@code error.account_deactivated} message and code.
 *
 * <p>Deliberately does NOT extend {@code BadCredentialsException} so the credential-masking
 * handler can never swallow it.
 */
public class AccountDeactivatedException extends RuntimeException {

    public AccountDeactivatedException() {
        super("error.account_deactivated");
    }
}
