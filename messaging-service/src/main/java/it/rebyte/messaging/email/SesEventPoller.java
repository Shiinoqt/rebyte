package it.rebyte.messaging.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Drains the SES events queue on one background thread. A message is deleted
 * only after it was processed; a failing one stays on the queue, so the
 * queue's redrive policy parks it in the dead-letter queue instead of losing it.
 *
 * <p>Failures are logged by exception class and message id only, because the
 * message body holds email addresses.
 */
@Component
public class SesEventPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SesEventPoller.class);

    private static final int WAIT_SECONDS = 20;
    private static final int MAX_MESSAGES = 10;
    private static final long BACKOFF_MS = 5_000;

    private final SqsClient sqs;
    private final SesEventProcessor processor;
    private final boolean enabled;
    private final String queueUrl;

    private volatile boolean running;
    private volatile Thread worker;

    public SesEventPoller(SqsClient sqs, SesEventProcessor processor,
            @Value("${rebyte.ses.events.enabled}") boolean enabled,
            @Value("${rebyte.ses.events.queue-url:}") String queueUrl) {
        if (enabled && queueUrl.isBlank()) {
            throw new IllegalStateException("SES_EVENTS_QUEUE_URL must be set when SES_EVENTS_ENABLED is true");
        }
        this.sqs = sqs;
        this.processor = processor;
        this.enabled = enabled;
        this.queueUrl = queueUrl;
    }

    @Override
    public void start() {
        if (!enabled || running) {
            return;
        }
        running = true;
        Thread thread = new Thread(this::loop, "ses-events-poller");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** One receive, process and delete cycle; returns how many messages were deleted. */
    public int pollOnce() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .waitTimeSeconds(WAIT_SECONDS)
                .maxNumberOfMessages(MAX_MESSAGES)
                .build();
        int deleted = 0;
        for (Message message : sqs.receiveMessage(request).messages()) {
            try {
                processor.process(message.body());
                sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());
                deleted++;
            } catch (RuntimeException e) {
                log.warn("SES event left on the queue sqsMessageId={} failure={}", message.messageId(),
                        e.getClass().getSimpleName());
            }
        }
        return deleted;
    }

    private void loop() {
        while (running) {
            try {
                pollOnce();
            } catch (SdkException e) {
                if (!running) {
                    return;
                }
                log.warn("SES events queue poll failed failure={}", e.getClass().getSimpleName());
                if (!backOff()) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("SES events poller iteration failed failure={}", e.getClass().getSimpleName());
                if (!backOff()) {
                    return;
                }
            }
        }
    }

    private boolean backOff() {
        try {
            Thread.sleep(BACKOFF_MS);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
