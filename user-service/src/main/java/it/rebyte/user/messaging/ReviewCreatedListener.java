package it.rebyte.user.messaging;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.user.config.RabbitConfig;
import it.rebyte.user.repository.SellerReviewRepository;

/**
 * Projects {@code review.created} into {@code seller_review} (D-15, D-47).
 *
 * <p>The projection is keyed by the review's own id, and a single
 * insert-if-absent makes at-least-once redelivery (D-15) a no-op, so a
 * replay can never inflate a seller's review count.
 *
 * <p>Invalid payloads are rejected without requeue and dead-lettered to
 * {@link RabbitConfig#REVIEW_CREATED_DLQ}. Any other failure, such as a
 * transient database error, propagates and the message is requeued.
 *
 * <p>The raw {@link Message} is read because no Jackson message converter
 * is configured and the outbox relay publishes plain JSON text.
 */
@Component
public class ReviewCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewCreatedListener.class);

    private static final int ID_MAX_LENGTH = 64;
    private static final int ASCII_MAX = 0x7F;

    private final SellerReviewRepository sellerReviewRepository;
    private final ObjectMapper objectMapper;

    public ReviewCreatedListener(SellerReviewRepository sellerReviewRepository, ObjectMapper objectMapper) {
        this.sellerReviewRepository = sellerReviewRepository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.REVIEW_CREATED_QUEUE)
    @Transactional
    public void onReviewCreated(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        ReviewCreatedEvent event;
        try {
            event = objectMapper.readValue(message.getBody(), ReviewCreatedEvent.class);
        } catch (IOException malformed) {
            throw reject(routingKey, malformed);
        }

        if (event == null || !isValidId(event.reviewId()) || !isValidId(event.sellerSub())
                || event.rating() == null || event.rating() < 0 || event.rating() > 5
                || event.occurredAt() == null) {
            throw reject(routingKey, null);
        }

        sellerReviewRepository.insertIfAbsent(
                event.reviewId(), event.sellerSub(), event.rating(), event.occurredAt());
    }

    // The projection columns are ascii VARCHAR(64); a longer or non-ascii value would raise a SQL error
    // that requeues forever instead of being dead-lettered.
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
