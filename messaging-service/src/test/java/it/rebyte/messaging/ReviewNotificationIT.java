package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.support.MessagingInfraTestBase;

@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = {
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *"
})
class ReviewNotificationIT extends MessagingInfraTestBase {

    private static final long DEADLINE_MS = 15_000;
    // Listener retry backs off 1 s, 2 s and 4 s before rejecting, and prefetch 1 serializes poison messages.
    private static final long DEAD_LETTER_DEADLINE_MS = 45_000;
    private static final long STABLE_MS = 750;

    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void purge() {
        rabbitAdmin.purgeQueue(RabbitConfig.REVIEW_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitConfig.REVIEW_DLQ, false);
    }

    @Test
    void reviewNotifiesTheSellerOnceWithRatingAndReviewTarget() {
        String sellerSub = sub();
        String reviewId = UUID.randomUUID().toString();

        publish(review(reviewId, sellerSub, "4"));

        await(() -> reviewsFor(sellerSub).size() == 1, "one REVIEW notification");
        Notification stored = reviewsFor(sellerSub).getFirst();
        assertThat(stored.getReviewId()).isEqualTo(reviewId);
        assertThat(stored.getDedupKey()).isEqualTo("REVIEW:" + reviewId);
        assertThat(stored.getSnapshot()).containsEntry("rating", "4");
        assertThat(stored.isRead()).isFalse();
    }

    @Test
    void zeroStarReviewIsValidBecauseOrderServiceAcceptsIt() {
        String sellerSub = sub();

        publish(review(UUID.randomUUID().toString(), sellerSub, "0"));

        await(() -> reviewsFor(sellerSub).size() == 1, "zero-star REVIEW notification");
        assertThat(reviewsFor(sellerSub).getFirst().getSnapshot()).containsEntry("rating", "0");
        assertThat(depth(RabbitConfig.REVIEW_DLQ)).isZero();
    }

    @Test
    void identicalRedeliveryKeepsExactlyOneNotification() {
        String sellerSub = sub();
        String body = review(UUID.randomUUID().toString(), sellerSub, "5");

        publish(body);
        publish(body);

        awaitDrained();
        await(() -> reviewsFor(sellerSub).size() == 1, "one REVIEW notification");
        sleep(STABLE_MS);
        assertThat(reviewsFor(sellerSub)).hasSize(1);
        assertThat(depth(RabbitConfig.REVIEW_DLQ)).isZero();
    }

    @Test
    void invalidRatingAndMissingReviewIdAreDeadLetteredWithoutNotifications() {
        String sellerSub = sub();

        publish(review(UUID.randomUUID().toString(), sellerSub, "9"));
        publish("{\"sellerSub\":\"" + sellerSub + "\",\"rating\":3,\"occurredAt\":\"2026-10-05T10:00:00Z\"}");
        publish("not json");

        await(() -> depth(RabbitConfig.REVIEW_DLQ) == 3, "three messages in " + RabbitConfig.REVIEW_DLQ,
                DEAD_LETTER_DEADLINE_MS);
        assertThat(depth(RabbitConfig.REVIEW_QUEUE)).isZero();
        assertThat(reviewsFor(sellerSub)).isEmpty();
    }

    private List<Notification> reviewsFor(String sellerSub) {
        return mongo.find(Query.query(Criteria.where("recipientSub").is(sellerSub)
                .and("type").is(NotificationType.REVIEW)), Notification.class);
    }

    private static String review(String reviewId, String sellerSub, String rating) {
        return "{\"reviewId\":\"" + reviewId + "\",\"sellerSub\":\"" + sellerSub + "\",\"rating\":" + rating
                + ",\"occurredAt\":\"2026-10-05T10:00:00Z\"}";
    }

    private static String sub() {
        return "seller-" + UUID.randomUUID();
    }

    private void publish(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .andProperties(properties).build();
        rabbit.send(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.REVIEW_CREATED, message);
    }

    private long depth(String queue) {
        var info = rabbitAdmin.getQueueInfo(queue);
        assertThat(info).as("queue %s declared", queue).isNotNull();
        return info.getMessageCount();
    }

    private void awaitDrained() {
        await(() -> depth(RabbitConfig.REVIEW_QUEUE) == 0, RabbitConfig.REVIEW_QUEUE + " drained");
    }

    private static void await(BooleanSupplier condition, String description) {
        await(condition, description, DEADLINE_MS);
    }

    private static void await(BooleanSupplier condition, String description, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(100);
        }
        assertThat(condition.getAsBoolean()).as("timed out waiting for %s", description).isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
