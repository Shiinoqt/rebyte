package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import it.rebyte.messaging.error.InvalidCursorException;
import it.rebyte.messaging.service.Cursors;
import it.rebyte.messaging.service.Cursors.InstantIdCursor;

class CursorsTest {

    private static String raw(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void instantAndIdRoundTrip() {
        Instant at = Instant.parse("2026-03-04T10:15:30.123Z");

        InstantIdCursor decoded = Cursors.decodeInstantId(Cursors.encodeInstantId(at, "conv-1"));

        assertThat(decoded).isEqualTo(new InstantIdCursor(at, "conv-1"));
    }

    @Test
    void idContainingTheSeparatorSurvivesTheRoundTrip() {
        Instant at = Instant.parse("2026-03-04T10:15:30Z");

        assertThat(Cursors.decodeInstantId(Cursors.encodeInstantId(at, "a|b")).id()).isEqualTo("a|b");
    }

    @Test
    void encodedCursorsAreUrlSafeWithoutPadding() {
        String cursor = Cursors.encodeInstantId(Instant.parse("2026-03-04T10:15:30Z"), "conv?>>~");

        assertThat(cursor).doesNotContain("=", "+", "/");
    }

    @Test
    void sequenceRoundTrips() {
        assertThat(Cursors.decodeSeq(Cursors.encodeSeq(1))).isEqualTo(1L);
        assertThat(Cursors.decodeSeq(Cursors.encodeSeq(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void defaultPageSizeIsTwenty() {
        assertThat(Cursors.pageSize(null)).isEqualTo(20);
    }

    @Test
    void sizesOneAndFiftyAreAccepted() {
        assertThat(Cursors.pageSize(1)).isEqualTo(1);
        assertThat(Cursors.pageSize(50)).isEqualTo(50);
    }

    @Test
    void sizesZeroAndFiftyOneAreRejected() {
        assertThatThrownBy(() -> Cursors.pageSize(0)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> Cursors.pageSize(51)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void invalidCursorIsABadRequestWithAStableCode() {
        InvalidCursorException failure = new InvalidCursorException();

        assertThat(failure.getStatus().value()).isEqualTo(400);
        assertThat(failure.getCode()).isEqualTo("invalid-cursor");
    }

    @Test
    void garbageInstantIdCursorIsRejected() {
        assertThatThrownBy(() -> Cursors.decodeInstantId("!!not-base64!!")).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> Cursors.decodeInstantId(raw("no-separator"))).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void absentCursorMeansFirstPage() {
        assertThat(Cursors.decodeInstantId(null)).isNull();
        assertThat(Cursors.decodeInstantId("")).isNull();
        assertThat(Cursors.decodeSeq(null)).isNull();
        assertThat(Cursors.decodeSeq(" ")).isNull();
    }

    @Test
    void instantIdCursorWithBadPartsIsRejected() {
        assertThatThrownBy(() -> Cursors.decodeInstantId(raw("not-an-instant|conv-1")))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> Cursors.decodeInstantId(raw("2026-03-04T10:15:30Z|")))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> Cursors.decodeInstantId(raw("2026-03-04T10:15:30Z|  ")))
                .isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> Cursors.decodeInstantId(raw("|conv-1"))).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void zeroAndNegativeSequencesAreRejected() {
        assertThatThrownBy(() -> Cursors.decodeSeq(raw("0"))).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> Cursors.decodeSeq(raw("-5"))).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> Cursors.encodeSeq(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonNumericOrOverflowingSequenceIsRejected() {
        assertThatThrownBy(() -> Cursors.decodeSeq(raw("abc"))).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> Cursors.decodeSeq(raw("99999999999999999999"))).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> Cursors.decodeSeq("!!!")).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void negativeAndHugeSizesAreRejected() {
        assertThatThrownBy(() -> Cursors.pageSize(-1)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> Cursors.pageSize(Integer.MAX_VALUE)).isInstanceOf(InvalidCursorException.class);
    }
}
