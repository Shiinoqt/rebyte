package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import it.rebyte.messaging.email.SesEventPoller;
import it.rebyte.messaging.email.SesEventProcessor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SesEventPollerTest {

    private static final String QUEUE_URL = "https://sqs.example.test/000000000000/ses-events";

    private final SqsClient sqs = mock(SqsClient.class);
    private final SesEventProcessor processor = mock(SesEventProcessor.class);

    @Test
    void enabledWithoutAQueueUrlRefusesToStart() {
        assertThatIllegalStateException().isThrownBy(() -> new SesEventPoller(sqs, processor, true, ""))
                .withMessageContaining("SES_EVENTS_QUEUE_URL");
        assertThatIllegalStateException().isThrownBy(() -> new SesEventPoller(sqs, processor, true, "  "));
    }

    @Test
    void disabledPollerNeverTouchesSqs() {
        SesEventPoller poller = new SesEventPoller(sqs, processor, false, "");

        poller.start();

        assertThat(poller.isRunning()).isFalse();
        verifyNoInteractions(sqs);
    }

    @Test
    void onlyTheMessageThatWasProcessedIsDeleted() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(message("m1", "r1", "{\"ok\":1}"), message("m2", "r2", "{\"bad\":1}"))
                .build());
        doThrow(new IllegalArgumentException("boom")).when(processor).process("{\"bad\":1}");
        SesEventPoller poller = new SesEventPoller(sqs, processor, true, QUEUE_URL);

        int deleted = poller.pollOnce();

        assertThat(deleted).isEqualTo(1);
        ArgumentCaptor<DeleteMessageRequest> deletes = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs).deleteMessage(deletes.capture());
        assertThat(deletes.getValue().receiptHandle()).isEqualTo("r1");
        assertThat(deletes.getValue().queueUrl()).isEqualTo(QUEUE_URL);
    }

    @Test
    void receiveRequestLongPollsTheConfiguredQueue() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().build());
        SesEventPoller poller = new SesEventPoller(sqs, processor, true, QUEUE_URL);

        assertThat(poller.pollOnce()).isZero();

        ArgumentCaptor<ReceiveMessageRequest> request = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqs).receiveMessage(request.capture());
        assertThat(request.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.getValue().waitTimeSeconds()).isEqualTo(20);
        assertThat(request.getValue().maxNumberOfMessages()).isEqualTo(10);
    }

    private static Message message(String id, String receiptHandle, String body) {
        return Message.builder().messageId(id).receiptHandle(receiptHandle).body(body).build();
    }
}
