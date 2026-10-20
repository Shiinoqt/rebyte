package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.service.ReadService;

class MarkReadWindowTest {

    @Test
    void newestSeqOfAnEmptyPageIsZero() {
        assertThat(ReadService.newestSeq(List.of())).isZero();
    }

    @Test
    void newestSeqIsTheHighestSeqOnThePage() {
        assertThat(ReadService.newestSeq(List.of(message(7), message(6), message(5)))).isEqualTo(7);
    }

    private static Message message(long seq) {
        return new Message("m" + seq, "c", seq, "a", "b", MessageKind.CHAT, "x", null, null, null, null, false, null);
    }
}
