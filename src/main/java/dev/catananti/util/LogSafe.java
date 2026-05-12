package dev.catananti.util;

/**
 * Sanitizes untrusted user-supplied strings before they reach the logger.
 * <p>
 * Prevents log-injection by stripping newlines, carriage returns, the
 * ANSI escape lead-in (ESC = 0x1B), and other non-printable control
 * characters that would let an attacker forge fake log entries or
 * manipulate terminal-based log viewers.
 */
public final class LogSafe {

    private static final int DEFAULT_MAX_LENGTH = 1024;
    private static final String TRUNCATION_MARKER = "...[truncated]";

    private LogSafe() {}

    /** Sanitize with the default 1 KiB cap. */
    public static String sanitize(String value) {
        return sanitize(value, DEFAULT_MAX_LENGTH);
    }

    /**
     * Sanitize {@code value} for safe logging, capping the result at
     * {@code maxLength} characters (a truncation marker is appended when
     * the input is longer).
     *
     * @param value raw user-supplied text (may be {@code null})
     * @param maxLength maximum characters to keep; values &lt; 16 are clamped to 16
     * @return a single-line, printable-only rendition of the input, or
     *         the literal string {@code "null"} when {@code value} is {@code null}
     */
    public static String sanitize(String value, int maxLength) {
        if (value == null) {
            return "null";
        }
        int cap = Math.max(maxLength, 16);
        int length = Math.min(value.length(), cap);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            // Strip C0 controls (including \r \n \t \b 0x1B/ESC) and DEL.
            if (c < 0x20 || c == 0x7F) {
                out.append('?');
            } else {
                out.append(c);
            }
        }
        if (value.length() > cap) {
            out.append(TRUNCATION_MARKER);
        }
        return out.toString();
    }
}
