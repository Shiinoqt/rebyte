package it.rebyte.commons.license;

import java.security.SecureRandom;

/**
 * Grammar, validator and generator for licence key patterns (D-24).
 *
 * <p>A pattern is 1 to 64 characters drawn from {@code A-Z}, {@code 0-9},
 * hyphen and {@code #}. Each {@code #} becomes one Crockford base32 character
 * and every other character is copied in place. At least 16 {@code #} are
 * required: 16 base32 characters carry 80 bits, which keeps a key
 * unguessable whatever pattern the developer picks.
 *
 * <p>catalog-service validates patterns on publish and edit with
 * {@link #isValid(String)}; order-service generates keys at payment with
 * {@link #generate(String)}. Both share this class so the grammar cannot drift.
 */
public final class LicenseKeyPattern {

    public static final String DEFAULT = "RBYT-#####-#####-#####-#####";

    /** Crockford base32: no I, L, O or U, so keys survive being read aloud or retyped. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    public static final int MIN_RANDOM_CHARACTERS = 16;

    public static final int MAX_LENGTH = 64;

    private static final char RANDOM_MARKER = '#';

    private static final SecureRandom RANDOM = new SecureRandom();

    private LicenseKeyPattern() {
    }

    public static boolean isValid(String pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.length() > MAX_LENGTH) {
            return false;
        }
        int randomCharacters = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == RANDOM_MARKER) {
                randomCharacters++;
            } else if (!isLiteral(c)) {
                return false;
            }
        }
        return randomCharacters >= MIN_RANDOM_CHARACTERS;
    }

    /**
     * @throws IllegalArgumentException when the pattern is invalid; the message is
     *     fixed so neither the pattern nor any key can leak through it
     */
    public static String generate(String pattern) {
        if (!isValid(pattern)) {
            throw new IllegalArgumentException("invalid licence key pattern");
        }
        StringBuilder key = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            key.append(c == RANDOM_MARKER ? ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())) : c);
        }
        return key.toString();
    }

    private static boolean isLiteral(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
    }
}
