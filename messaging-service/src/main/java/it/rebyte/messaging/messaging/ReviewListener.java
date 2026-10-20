package it.rebyte.messaging.messaging;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.service.ReviewNotificationService;

/**
 * Turns {@code review.created} into a REVIEW notification for the seller (NOTF-05).
 *
 * <p>Invalid payloads are rejected without requeue and land in {@link RabbitConfig#REVIEW_DLQ}.
 * The rating range is 0 to 5 because order-service accepts a zero-star review; any other
 * failure propagates so the message is retried.
 */
@Component
public class ReviewListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewListener.class);

    private static final int ID_MAX_LENGTH = 64;
    private static final int ASCII_MAX = 0x7F;

    private final ReviewNotificationService reviews;
    private final ObjectMapper objectMapper;

    public ReviewListener(ReviewNotificationService reviews, ObjectMapper objectMapper) {
        this.reviews = reviews;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.REVIEW_QUEUE)
    public void onReviewCreated(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        ReviewEvent event;
        try {
            event = objectMapper.readValue(message.getBody(), ReviewEvent.class);
        } catch (IOException malformed) {
            throw reject(routingKey, malformed);
        }
        if (event == null || !isValidId(event.reviewId()) || !isValidId(event.sellerSub())
                || event.rating() == null || event.rating() < 0 || event.rating() > 5
                || event.occurredAt() == null) {
            throw reject(routingKey, null);
        }
        reviews.created(event);
    }

    private static boolean isValidId(String value) {
        return value != null && !value.isBlank() && value.length() <= ID_MAX_LENGTH
                && value.chars().allMatch(c -> c <= ASCII_MAX);
    }

    private AmqpRejectAndDontRequeueException reject(String routingKey, Throwable cause) {
        log.warn("Rejecting invalid review event routingKey={}", routingKey);
        return cause == null
                ? new AmqpRejectAndDontRequeueException("Invalid review event")
                : new AmqpRejectAndDontRequeueException("Invalid review event", cause);
    }
}
