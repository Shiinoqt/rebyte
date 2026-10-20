package it.rebyte.user.service;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import it.rebyte.user.error.InvalidUsernameException;

/**
 * Handle validation and normalisation rules (D-59, D-60) — a small
 * service-layer class, not a scattered set of annotations.
 *
 * <p>Permitted characters are lowercase ASCII letters, digits, underscore
 * and hyphen; length 3 to 30 inclusive; input is normalised to lowercase
 * before validation and before storage. Accents and non-Latin scripts are
 * excluded on purpose (D-60): allowing accents while D-58's collation
 * treats them as equivalent would be a surprising combination, and Unicode
 * makes visually identical handles trivial — a Cyrillic letter that renders
 * identically to a Latin one is an impersonation vector on a marketplace
 * where a buyer decides who to send money to. Real names belong in {@code
 * bio}.
 *
 * <p>A rename is rejected when the requested value matches the
 * <em>generated-handle shape</em> — the literal prefix {@code user_}
 * followed by exactly eight hexadecimal characters, matched
 * case-insensitively — and only that shape (D-59). Blocking the whole
 * {@code user_} prefix would needlessly forbid ordinary names such as
 * {@code user_friendly}; blocking only the precise shape prevents a chosen
 * handle from ever equalling a future generated one (see {@link
 * UserService#generateHandle(String)}), which would otherwise make a later
 * user's JIT provisioning fail on the unique index — a hard signup failure
 * caused by an unrelated rename months earlier.
 */
@Component
public class UsernameRules {

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 30;
    private static final Pattern ALLOWED_CHARS = Pattern.compile("^[a-z0-9_-]+$");
    private static final Pattern RESERVED_SHAPE = Pattern.compile("^user_[0-9a-f]{8}$");

    /**
     * Normalises to lowercase and validates character set and length. Does
     * NOT check the reserved-shape rule — see {@link #isReservedShape}.
     *
     * @throws InvalidUsernameException if the normalised value violates the
     *     character-set or length rules
     */
    public String normalizeAndValidate(String rawUsername) {
        if (rawUsername == null) {
            throw new InvalidUsernameException("username must not be null");
        }
        String normalized = rawUsername.toLowerCase(Locale.ROOT);
        if (normalized.length() < MIN_LENGTH || normalized.length() > MAX_LENGTH) {
            throw new InvalidUsernameException(
                    "username must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        if (!ALLOWED_CHARS.matcher(normalized).matches()) {
            throw new InvalidUsernameException(
                    "username may only contain lowercase letters, digits, underscore and hyphen");
        }
        return normalized;
    }

    /**
     * True when {@code normalizedUsername} matches the generated-handle
     * shape ({@link UserService#generateHandle(String)}) exactly — not the
     * whole {@code user_} prefix.
     */
    public boolean isReservedShape(String normalizedUsername) {
        return RESERVED_SHAPE.matcher(normalizedUsername).matches();
    }
}
