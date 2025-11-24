package dev.catananti.exception;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * Exception thrown when a user account is temporarily locked due to too many failed login attempts.
 */
public class AccountLockedException extends BadCredentialsException {

    private final long remainingMinutes;

    public AccountLockedException(long remainingMinutes) {
        super("Account temporarily locked");
        this.remainingMinutes = remainingMinutes;
    }

    public long getRemainingMinutes() {
        return remainingMinutes;
    }
}
