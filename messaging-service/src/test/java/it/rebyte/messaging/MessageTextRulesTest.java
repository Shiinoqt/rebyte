package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import it.rebyte.messaging.service.MessageTextRules;
import it.rebyte.messaging.error.InvalidMessageTextException;

class MessageTextRulesTest {

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(MessageTextRules.normalize(" \n Hello \t ")).isEqualTo("Hello");
    }

    @Test
    void nullEmptyAndWhitespaceOnlyAreRejected() {
        assertInvalid(null);
        assertInvalid("");
        assertInvalid("   ");
        assertInvalid("\t\n\r \u2003");
    }

    @Test
    void maximumLengthCountsCodePointsInsteadOfUtf16Units() {
        String maximum = "😀".repeat(2000);
        assertThat(maximum.length()).isEqualTo(4000);
        assertThat(MessageTextRules.normalize(maximum)).isEqualTo(maximum);
        assertInvalid("😀".repeat(2001));
    }

    @Test
    void allForbiddenControlsAreRejected() {
        for (int control = 0; control < 0x20; control++) {
            if (control != '\t' && control != '\n' && control != '\r') {
                assertInvalid("before" + (char) control + "after");
            }
        }
        assertInvalid("before\u007fafter");
    }

    @Test
    void embeddedTabsAndLineBreaksAreKept() {
        assertThat(MessageTextRules.normalize("a\tb\nc\rd")).isEqualTo("a\tb\nc\rd");
    }

    @Test
    void previewTruncatesAtCodePointBoundary() {
        String text = "😀".repeat(141);
        assertThat(MessageTextRules.preview(text)).isEqualTo("😀".repeat(140));
        assertThat(MessageTextRules.preview(text).length()).isEqualTo(280);
        assertThat(MessageTextRules.preview("a".repeat(140))).isEqualTo("a".repeat(140));
        assertThat(MessageTextRules.preview("short")).isEqualTo("short");
        assertThat(MessageTextRules.preview(null)).isNull();
    }

    private static void assertInvalid(String text) {
        assertThatThrownBy(() -> MessageTextRules.normalize(text)).isInstanceOf(InvalidMessageTextException.class);
    }
}
