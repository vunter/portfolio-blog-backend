package dev.catananti.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for password complexity validation.
 * Enforces: min 12 chars, at least 1 uppercase, 1 lowercase, 1 digit, 1 special character.
 */
public final class PasswordValidator {

    private static final int MIN_LENGTH = 12;

    private PasswordValidator() {
    }

    public static boolean isComplexEnough(String password) {
        return getComplexityErrors(password).isEmpty();
    }

    public static List<String> getComplexityErrors(String password) {
        List<String> errors = new ArrayList<>();
        if (password == null || password.length() < MIN_LENGTH) {
            errors.add("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password == null) {
            return errors;
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            errors.add("Password must contain at least one uppercase letter");
        }
        if (password.chars().noneMatch(Character::isLowerCase)) {
            errors.add("Password must contain at least one lowercase letter");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            errors.add("Password must contain at least one digit");
        }
        if (password.chars().allMatch(c -> Character.isLetterOrDigit(c))) {
            errors.add("Password must contain at least one special character");
        }
        return errors;
    }
}
