package com.deepon.smartdocs.user;

import com.deepon.smartdocs.common.exception.ValidationFailedException;
import com.deepon.smartdocs.common.exception.ValidationFailedException.FieldViolation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Manual validation, same style as {@code ContentValidator} — no Bean
 * Validation annotations, to keep one validation approach across the
 * codebase. Design doc section 9: composition rules are deliberately not
 * enforced; length plus a breached-password check does more for entropy
 * than forcing {@code Passw0rd!}-shaped passwords.
 *
 * {@link #COMMON_PASSWORDS} is a small illustrative blocklist, not a real
 * top-10k breached-password corpus — sourcing and embedding one is outside
 * this stage's scope (recorded as a follow-up, same spirit as design doc
 * section 15's deliberate-debts table). Swap in a real list before this
 * code sees real user data.
 */
@Component
public class UserValidator {

    private static final int MIN_EMAIL_LENGTH = 3;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 200;
    private static final int MAX_DISPLAY_NAME_LENGTH = 80;

    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "123456789012", "password1234", "qwertyuiopas", "letmein123456", "welcome123456",
            "admin12345678", "iloveyou12345", "princess12345", "1q2w3e4r5t6y", "sunshine12345",
            "password12345", "123456123456", "football123456", "monkey12345678", "abc123456789");

    public record RegistrationInput(String email, String displayName) {
    }

    /**
     * @throws ValidationFailedException carrying every violated field, not just the first.
     */
    public RegistrationInput validateRegistration(String rawEmail, String rawPassword, String rawDisplayName) {
        List<FieldViolation> violations = new ArrayList<>();
        String email = rawEmail == null ? "" : rawEmail.trim();
        String displayName = rawDisplayName == null ? "" : rawDisplayName.trim();

        validateEmail(email, violations);
        validatePassword(rawPassword, violations);
        validateDisplayName(displayName, violations);

        if (!violations.isEmpty()) {
            throw new ValidationFailedException(violations);
        }
        return new RegistrationInput(email, displayName);
    }

    public void validatePasswordOrThrow(String rawPassword) {
        List<FieldViolation> violations = new ArrayList<>();
        validatePassword(rawPassword, violations);
        if (!violations.isEmpty()) {
            throw new ValidationFailedException(violations);
        }
    }

    private void validateEmail(String email, List<FieldViolation> violations) {
        if (email.length() < MIN_EMAIL_LENGTH || email.length() > MAX_EMAIL_LENGTH) {
            violations.add(new FieldViolation("email", "email must be between " + MIN_EMAIL_LENGTH + " and " + MAX_EMAIL_LENGTH + " characters"));
            return;
        }
        if (!EMAIL_SHAPE.matcher(email).matches()) {
            violations.add(new FieldViolation("email", "email is not a valid address"));
        }
    }

    private void validatePassword(String password, List<FieldViolation> violations) {
        if (password == null || password.isEmpty()) {
            violations.add(new FieldViolation("password", "password is required"));
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            violations.add(new FieldViolation("password", "password must be between " + MIN_PASSWORD_LENGTH + " and " + MAX_PASSWORD_LENGTH + " characters"));
        }
        for (int i = 0; i < password.length(); i++) {
            if (Character.isISOControl(password.charAt(i))) {
                violations.add(new FieldViolation("password", "password must not contain control characters"));
                break;
            }
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            violations.add(new FieldViolation("password", "password is too common"));
        }
    }

    private void validateDisplayName(String displayName, List<FieldViolation> violations) {
        if (displayName.isEmpty()) {
            violations.add(new FieldViolation("displayName", "displayName must not be blank"));
        } else if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            violations.add(new FieldViolation("displayName", "displayName exceeds " + MAX_DISPLAY_NAME_LENGTH + " characters"));
        }
    }
}
