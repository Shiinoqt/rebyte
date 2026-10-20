package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import it.rebyte.user.error.InvalidUsernameException;
import it.rebyte.user.service.UsernameRules;

class UsernameRulesTest {

    private final UsernameRules rules = new UsernameRules();

    @Test
    void normalizesMixedCaseToLowercase() {
        assertThat(rules.normalizeAndValidate("Seller")).isEqualTo("seller");
    }

    @Test
    void rejectsBelowMinimumLength() {
        assertThatThrownBy(() -> rules.normalizeAndValidate("ab"))
                .isInstanceOf(InvalidUsernameException.class);
    }

    @Test
    void rejectsAboveMaximumLength() {
        String tooLong = "a".repeat(31);
        assertThatThrownBy(() -> rules.normalizeAndValidate(tooLong))
                .isInstanceOf(InvalidUsernameException.class);
    }

    @Test
    void acceptsMinimumAndMaximumLength() {
        assertThat(rules.normalizeAndValidate("abc")).isEqualTo("abc");
        String max = "a".repeat(30);
        assertThat(rules.normalizeAndValidate(max)).isEqualTo(max);
    }

    @Test
    void rejectsDisallowedCharacters() {
        assertThatThrownBy(() -> rules.normalizeAndValidate("seller name"))
                .isInstanceOf(InvalidUsernameException.class);
        assertThatThrownBy(() -> rules.normalizeAndValidate("seller!"))
                .isInstanceOf(InvalidUsernameException.class);
        assertThatThrownBy(() -> rules.normalizeAndValidate("séller"))
                .isInstanceOf(InvalidUsernameException.class);
    }

    @Test
    void acceptsAllowedCharacterClasses() {
        assertThat(rules.normalizeAndValidate("seller_the-first99")).isEqualTo("seller_the-first99");
    }

    @Test
    void reservedShapeIsRecognized() {
        assertThat(rules.isReservedShape("user_1a2b3c4d")).isTrue();
    }

    @Test
    void lookalikesOfReservedShapeAreNotFlagged() {
        assertThat(rules.isReservedShape("user_friendly")).isFalse();
        assertThat(rules.isReservedShape("user_group")).isFalse();
    }
}
