package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.EmailDispatch;
import it.rebyte.messaging.domain.EmailPreference;
import it.rebyte.messaging.domain.EmailSuppression;
import it.rebyte.messaging.domain.FavouriteProjection;
import it.rebyte.messaging.domain.FollowState;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.messaging.FavouriteEvent;
import it.rebyte.messaging.messaging.FollowEvent;
import it.rebyte.messaging.messaging.ListingPriceEvent;
import it.rebyte.messaging.messaging.OfferEvent;
import it.rebyte.messaging.messaging.ReviewEvent;
import it.rebyte.messaging.messaging.VersionReleasedEvent;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.outbox.OutboxRelay;
import it.rebyte.messaging.support.MessagingInfraTestBase;
import it.rebyte.messaging.support.StubHttpServer;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = {
        "rebyte.ses.min-send-interval-ms=0",
        "rebyte.outbox.relay.fixed-delay-ms=3600000", "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms",
        "spring.rabbitmq.listener.simple.retry.max-retries=3"
})
class RedeliveryDedupIT extends MessagingInfraTestBase {
    private static final StubHttpServer USERS = new StubHttpServer();
    private static final StubHttpServer ORDERS = new StubHttpServer();
    private static final Instant TIME = Instant.parse("2026-10-05T10:00:00Z");
    private static final List<Class<?>> STATE_TYPES = List.of(Notification.class, Message.class, Conversation.class,
            EmailDispatch.class, OutboxEvent.class, FavouriteProjection.class, FollowState.class);
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired OutboxRelay relay;
    @MockitoBean SesV2Client ses;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.user-service.url", USERS::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.order-service.url", ORDERS::baseUrl);
    }

    @AfterAll
    static void closeStubs() {
        USERS.close();
        ORDERS.close();
    }

    @BeforeEach
    void cleanAndStub() throws Exception {
        RabbitConfig.DEAD_LETTER_ORIGINS.forEach((dlq, queue) -> {
            admin.purgeQueue(queue, false);
            admin.purgeQueue(dlq, false);
        });
        for (Class<?> type : STATE_TYPES) {
            mongo.remove(new Query(), type);
        }
        mongo.remove(new Query(), EmailPreference.class);
        mongo.remove(new Query(), EmailSuppression.class);
        for (String sub : List.of("follower", "favouriter", "buyer", "seller", "holder", "shared", "release-follower")) {
            USERS.respond("/internal/users/" + sub, 200, mapper.writeValueAsString(Map.of("sub", sub, "username", sub)));
            USERS.respond("/internal/users/" + sub + "/email", 200,
                    mapper.writeValueAsString(Map.of("sub", sub, "email", sub + "@example.test", "verified", true)));
        }
        ORDERS.respond("/internal/licences/holders?listingId=software&size=100", 200,
                "{\"buyerSubs\":[\"holder\",\"shared\",\"seller\"],\"nextCursor\":null}");
        USERS.respond("/internal/users/seller/followers?limit=500", 200,
                "{\"subs\":[\"shared\",\"release-follower\",\"seller\"],\"nextCursor\":null}");
        seedFavourite("price-a", true);
        seedFavourite("price-b", true);
        seedFavourite("inactive", false);
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("dedup-email").build());
    }

    @Test
    void everyConsumerDeduplicatesDoubleDeliveryAndParksPoisonWithoutChangingState() throws Exception {
        publishTwice(RebyteExchanges.USER_FOLLOWED,
                new FollowEvent(id(), "user.followed", "follower", "seller", TIME, 1L));
        publishTwice(RebyteExchanges.LISTING_FAVOURITED,
                new FavouriteEvent(id(), "favouriter", "favourite-listing", "seller", "Keyboard", TIME, 1L));
        publishTwice(RebyteExchanges.LISTING_UPDATED,
                new ListingPriceEvent("price-listing", 2, TIME, new BigDecimal("100"), new BigDecimal("90"), "Monitor"));
        publishTwice(RebyteExchanges.LISTING_VERSION_RELEASED,
                new VersionReleasedEvent("software", "seller", "2.0", "Editor"));
        publishTwice(RebyteExchanges.REVIEW_CREATED, new ReviewEvent(id(), "seller", 4, TIME));
        List<String> offerKeys = List.of(RebyteExchanges.OFFER_CREATED, RebyteExchanges.OFFER_ACCEPTED,
                RebyteExchanges.OFFER_REJECTED, RebyteExchanges.OFFER_WITHDRAWN, RebyteExchanges.OFFER_EXPIRED);
        List<String> statuses = List.of("PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN", "EXPIRED");
        List<String> offerEventIds = new ArrayList<>();
        for (int index = 0; index < offerKeys.size(); index++) {
            String eventId = id();
            offerEventIds.add(eventId);
            publishTwice(offerKeys.get(index), new OfferEvent(eventId, "offer", "offer-listing", "Keyboard",
                    "buyer", "seller", BigDecimal.TEN, new BigDecimal("20"), statuses.get(index),
                    index == 2 ? "TOO_LOW" : null, TIME.plusSeconds(3600), TIME.plusSeconds(index)));
        }
        List<String> orderKeys = List.of(RebyteExchanges.ORDER_PAID, RebyteExchanges.ORDER_SHIPPED,
                RebyteExchanges.ORDER_DELIVERED, RebyteExchanges.ORDER_REFUNDED,
                RebyteExchanges.ORDER_PROBLEM_OPENED, RebyteExchanges.ORDER_PROBLEM_RESOLVED);
        for (String key : orderKeys) {
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", id());
            event.put("orderId", "order");
            event.put("listingId", "order-listing");
            event.put("listingType", "HARDWARE");
            event.put("listingTitle", "Keyboard");
            event.put("buyerSub", "buyer");
            event.put("sellerSub", "seller");
            event.put("occurredAt", TIME.toString());
            event.put("carrier", "BRT");
            event.put("trackingCode", "TRACK-1");
            event.put("refundedFromStatus", "SHIPPED");
            event.put("reason", "DAMAGED");
            event.put("outcome", "REFUND");
            publishTwice(key, event);
        }
        for (String queue : RabbitConfig.DEAD_LETTER_ORIGINS.values()) {
            barrier(queue);
        }

        List<Notification> notifications = mongo.findAll(Notification.class);
        assertThat(notifications).hasSize(17);
        Map<NotificationType, Long> counts = notifications.stream()
                .collect(Collectors.groupingBy(Notification::getType, Collectors.counting()));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                NotificationType.FOLLOW, 1L, NotificationType.FAVOURITE, 1L, NotificationType.PRICE_DROP, 2L,
                NotificationType.RELEASE, 3L, NotificationType.REVIEW, 1L, NotificationType.OFFER_RECEIVED, 1L,
                NotificationType.OFFER_ACCEPTED, 1L, NotificationType.ORDER, 7L));
        assertThat(notifications).allSatisfy(notification -> {
            assertThat(notification.getRecipientSub()).isNotBlank();
            assertThat(notification.getDedupKey()).isNotBlank();
        });
        assertThat(notifications.stream().collect(Collectors.groupingBy(notification ->
                List.of(notification.getRecipientSub(), notification.getDedupKey()), Collectors.counting())).values())
                .containsOnly(1L);
        assertThat(mongo.findAll(Message.class)).hasSize(5).allSatisfy(message ->
                assertThat(message.getKind()).isEqualTo(MessageKind.OFFER));
        assertThat(mongo.findAll(Message.class)).extracting(Message::getSourceEventId)
                .containsExactlyInAnyOrderElementsOf(offerEventIds);

        List<OutboxEvent> rows = mongo.findAll(OutboxEvent.class);
        assertThat(rows).hasSize(4).allSatisfy(row ->
                assertThat(row.getRoutingKey()).isEqualTo(RabbitConfig.EMAIL_SEND_ROUTING_KEY));
        List<EmailJob> distinctJobs = new ArrayList<>();
        for (OutboxEvent row : rows) {
            EmailJob job = mapper.readValue(row.getPayload(), EmailJob.class);
            assertThat(row.getEventId()).isEqualTo(job.eventId());
            distinctJobs.add(job);
        }
        assertThat(distinctJobs).extracting(EmailJob::eventId).doesNotHaveDuplicates();
        assertThat(distinctJobs).extracting(EmailJob::recipientSub)
                .containsExactlyInAnyOrder("holder", "shared", "release-follower", "buyer");
        relay.drainUntilEmptyOrCapped();
        for (OutboxEvent row : rows) {
            send(RebyteExchanges.MARKETPLACE_EVENTS, RabbitConfig.EMAIL_SEND_ROUTING_KEY,
                    row.getPayload().getBytes(StandardCharsets.UTF_8));
        }
        barrier(RabbitConfig.EMAIL_SEND_QUEUE);
        assertThat(mongo.findAll(EmailDispatch.class)).hasSize(distinctJobs.size()).allSatisfy(dispatch ->
                assertThat(dispatch.status()).isEqualTo(EmailDispatch.Status.SENT));
        assertThat(mongo.findAll(EmailDispatch.class)).extracting(EmailDispatch::id)
                .containsExactlyInAnyOrderElementsOf(distinctJobs.stream().map(EmailDispatch::idFor).toList())
                .doesNotHaveDuplicates();
        verify(ses, times(distinctJobs.size())).sendEmail(any(SendEmailRequest.class));
        RabbitConfig.DEAD_LETTER_ORIGINS.forEach((dlq, queue) -> {
            assertThat(depth(queue)).isZero();
            assertThat(depth(dlq)).isZero();
        });
        Map<String, List<Document>> beforePoison = snapshot();

        byte[] poison = "not JSON".getBytes(StandardCharsets.UTF_8);
        // offer.created reaches both offer consumers, so one publication covers their two DLQs.
        for (String key : List.of(RebyteExchanges.USER_FOLLOWED, RebyteExchanges.LISTING_FAVOURITED,
                RebyteExchanges.LISTING_UPDATED, RebyteExchanges.LISTING_VERSION_RELEASED,
                RebyteExchanges.REVIEW_CREATED, RebyteExchanges.OFFER_CREATED, RebyteExchanges.ORDER_PAID,
                RabbitConfig.EMAIL_SEND_ROUTING_KEY)) {
            send(RebyteExchanges.MARKETPLACE_EVENTS, key, poison);
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                RabbitConfig.DEAD_LETTER_ORIGINS.forEach((dlq, queue) -> {
                    assertThat(depth(queue)).isZero();
                    assertThat(depth(dlq)).isEqualTo(1);
                }));
        assertThat(snapshot()).isEqualTo(beforePoison);
        verify(ses, times(distinctJobs.size())).sendEmail(any(SendEmailRequest.class));
        for (var entry : RabbitConfig.DEAD_LETTER_ORIGINS.entrySet()) {
            var rejected = rabbit.receive(entry.getKey(), 1000);
            assertThat(rejected).isNotNull();
            assertThat(rejected.getBody()).isEqualTo(poison);
            assertThat(rejected.getMessageProperties().getHeaders().get("x-death"))
                    .isInstanceOf(List.class);
            List<?> deaths = (List<?>) rejected.getMessageProperties().getHeaders().get("x-death");
            assertThat(String.valueOf(((Map<?, ?>) deaths.getFirst()).get("queue"))).isEqualTo(entry.getValue());
        }
    }

    private void seedFavourite(String sub, boolean active) {
        mongo.insert(new FavouriteProjection(sub + "|price-listing", sub, "price-listing", "seller", active,
                TIME.minusSeconds(1), TIME.minusSeconds(1).toString(), "seed-" + sub, 1L));
    }

    private void publishTwice(String key, Object event) throws Exception {
        byte[] body = mapper.writeValueAsBytes(event);
        send(RebyteExchanges.MARKETPLACE_EVENTS, key, body);
        send(RebyteExchanges.MARKETPLACE_EVENTS, key, body);
    }

    private void send(String exchange, String key, byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(exchange, key, new org.springframework.amqp.core.Message(body, properties));
    }

    private void barrier(String queue) {
        // With prefetch 1, rejecting a unique FIFO marker proves even no-op replays finished.
        byte[] marker = ("barrier-" + id()).getBytes(StandardCharsets.UTF_8);
        send("", queue, marker);
        var rejected = rabbit.receive(queue + ".dlq", 15_000);
        assertThat(rejected).as("barrier for %s", queue).isNotNull();
        assertThat(rejected.getBody()).isEqualTo(marker);
        assertThat(depth(queue)).isZero();
        assertThat(depth(queue + ".dlq")).isZero();
    }

    private Map<String, List<Document>> snapshot() {
        Map<String, List<Document>> result = new HashMap<>();
        for (Class<?> type : STATE_TYPES) {
            String collection = mongo.getCollectionName(type);
            result.put(collection, mongo.getCollection(collection).find().sort(new Document("_id", 1))
                    .into(new ArrayList<>()));
        }
        return result;
    }

    private long depth(String queue) {
        var info = admin.getQueueInfo(queue);
        assertThat(info).isNotNull();
        return info.getMessageCount();
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
