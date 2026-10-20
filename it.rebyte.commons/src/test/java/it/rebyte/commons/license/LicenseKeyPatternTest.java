package it.rebyte.commons.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Hermetic proof of the D-24 grammar: boundaries of {@code isValid} and the
 * charset, layout and distinctness of {@code generate}.
 */
class LicenseKeyPatternTest {

    private static final String KEY_CHAR = "[0-9A-HJKMNP-TV-Z]";

    @Test
    void defaultPatternIsValidWithTwentyRandomCharacters() {
        assertThat(LicenseKeyPattern.isValid(LicenseKeyPattern.DEFAULT)).isTrue();
        assertThat(LicenseKeyPattern.DEFAULT.chars().filter(c -> c == '#').count()).isEqualTo(20);
        assertThat(LicenseKeyPattern.DEFAULT).hasSize(28);
    }

    @Test
    void sixteenRandomCharactersAreTheFloor() {
        assertThat(LicenseKeyPattern.isValid("#".repeat(16))).isTrue();
        assertThat(LicenseKeyPattern.isValid("#".repeat(15))).isFalse();
    }

    @Test
    void lengthIsCappedAtSixtyFour() {
        String atLimit = "#".repeat(16) + "A".repeat(48);
        String overLimit = "#".repeat(16) + "A".repeat(49);
        assertThat(atLimit).hasSize(64);
        assertThat(LicenseKeyPattern.isValid(atLimit)).isTrue();
        assertThat(overLimit).hasSize(65);
        assertThat(LicenseKeyPattern.isValid(overLimit)).isFalse();
    }

    @Test
    void nullAndEmptyAreInvalid() {
        assertThat(LicenseKeyPattern.isValid(null)).isFalse();
        assertThat(LicenseKeyPattern.isValid("")).isFalse();
    }

    @Test
    void charactersOutsideTheGrammarAreInvalid() {
        assertThat(LicenseKeyPattern.isValid("rbyt-################")).isFalse();
        assertThat(LicenseKeyPattern.isValid("RBYT ################")).isFalse();
        assertThat(LicenseKeyPattern.isValid("RBYT_################")).isFalse();
    }

    @Test
    void generatedDefaultKeyMatchesTheLayout() {
        String key = LicenseKeyPattern.generate(LicenseKeyPattern.DEFAULT);
        assertThat(key).matches("RBYT-" + KEY_CHAR + "{5}(-" + KEY_CHAR + "{5}){3}");
    }

    @Test
    void generateKeepsLiteralCharactersInPlace() {
        String key = LicenseKeyPattern.generate("AB-################-CD");
        assertThat(key).hasSize(22);
        assertThat(key).startsWith("AB-").endsWith("-CD");
        assertThat(key.substring(3, 19)).matches(KEY_CHAR + "{16}");
    }

    @Test
    void thousandGeneratedKeysAreDistinctAndAvoidAmbiguousLetters() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String key = LicenseKeyPattern.generate(LicenseKeyPattern.DEFAULT);
            String randomPart = key.substring("RBYT-".length());
            assertThat(randomPart).doesNotContain("I", "L", "O", "U");
            keys.add(key);
        }
        assertThat(keys).hasSize(1000);
    }

    @Test
    void generateRefusesAnInvalidPatternWithoutEchoingIt() {
        assertThatThrownBy(() -> LicenseKeyPattern.generate("ABC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid licence key pattern");
    }
}
