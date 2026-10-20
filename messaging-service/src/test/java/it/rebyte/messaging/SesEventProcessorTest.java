package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import it.rebyte.messaging.email.SesEventProcessor;
import it.rebyte.messaging.email.SuppressionService;

class SesEventProcessorTest {

    private final SuppressionService suppressions = mock(SuppressionService.class);
    private final SesEventProcessor processor = new SesEventProcessor(new ObjectMapper(), suppressions);

    @Test
    void permanentBounceRecordsEveryRecipientWithTheSubtype() {
        processor.process(fixture("bounce-permanent"));

        verify(suppressions).record(eqAddress("bounced.one@example.test"), eqString("BOUNCE_PERMANENT"),
                eqString("General"), eqString("ses-message-bounce-permanent-0001"), any(Instant.class));
        verify(suppressions).record(eqAddress("second.bounced@example.test"), eqString("BOUNCE_PERMANENT"),
                eqString("General"), eqString("ses-message-bounce-permanent-0001"), any(Instant.class));
        verifyNoMoreInteractions(suppressions);
    }

    @Test
    void complaintRecordsTheRecipientWithTheFeedbackType() {
        processor.process(fixture("complaint"));

        verify(suppressions).record(eqAddress("complainer@example.test"), eqString("COMPLAINT"), eqString("abuse"),
                eqString("ses-message-complaint-0003"), any(Instant.class));
        verifyNoMoreInteractions(suppressions);
    }

    @Test
    void transientBounceRejectAndDeliveryRecordNothing() {
        processor.process(fixture("bounce-transient"));
        processor.process(fixture("reject"));
        processor.process(fixture("delivery"));

        verify(suppressions, never()).record(anyString(), anyString(), any(), any(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Bad content", "private-provider-detail", "Virus"})
    void rejectLogsOnlyEventMetadata(String reason) throws IOException {
        var event = new ObjectMapper().readTree(fixture("reject"));
        ((ObjectNode) event.path("reject")).put("reason", reason);
        var logger = (Logger) LoggerFactory.getLogger(SesEventProcessor.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            processor.process(event.toString());

            assertThat(events.list).singleElement().satisfies(logEvent -> {
                assertThat(logEvent.getFormattedMessage())
                        .isEqualTo("SES reject eventType=Reject sesMessageId=ses-message-reject-0004");
                assertThat(logEvent.getThrowableProxy()).isNull();
            });
            verify(suppressions, never()).record(anyString(), anyString(), any(), any(), any());
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void malformedBodiesAreRejectedWithoutEchoingTheirContent() {
        assertThatIllegalArgumentException().isThrownBy(() -> processor.process("{not json for leak@example.test"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("example.test").doesNotContain("leak"));
        assertThatIllegalArgumentException().isThrownBy(() -> processor.process("[]"));
        assertThatIllegalArgumentException().isThrownBy(() -> processor.process(""));
        assertThatIllegalArgumentException().isThrownBy(() -> processor.process(null));
        assertThatIllegalArgumentException().isThrownBy(() -> processor.process("{\"mail\":{}}"));
        assertThatIllegalArgumentException().isThrownBy(() -> processor.process("{\"eventType\":null}"));
    }

    private static String eqAddress(String address) {
        return org.mockito.ArgumentMatchers.eq(address);
    }

    private static String eqString(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static String fixture(String name) {
        try (InputStream in = SesEventProcessorTest.class.getResourceAsStream("/ses/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
