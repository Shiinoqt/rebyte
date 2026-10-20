package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.FavouriteProjection;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.support.MessagingInfraTestBase;

@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.concurrency=1",
        "spring.rabbitmq.listener.simple.max-concurrency=1",
        "rebyte.outbox.relay.fixed-delay-ms=3600000"
})
class PriceDropNotificationIT extends MessagingInfraTestBase {

    private static final Instant TIME = Instant.parse("2026-10-05T10:15:30.123456789Z");
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired RealtimePublisher publisher;
    private RedisMessageListenerContainer subscriber;

    @BeforeEach
    void clean() {
        drain();
        mongo.remove(new Query(), Notification.class);
        mongo.remove(new Query(), FavouriteProjection.class);
        admin.purgeQueue(RabbitConfig.PRICE_DROP_DLQ, false);
        seed("L", "alice", true);
        seed("L", "bob", true);
        seed("L", "carol", true);
        seed("L", "inactive", false);
        seed("other", "unrelated", true);
    }

    @AfterEach
    void finish() throws Exception {
        drain();
        if (subscriber != null) {
            subscriber.stop();
            subscriber.destroy();
        }
    }

    @Test
    void activeFavouritersGetExactSnapshotAndMillisecondLinkOnceAndArePushed() throws Exception {
        var messages = subscribe();
        String json = event("L", "110.00", "95.00", TIME);
        publish(json);
        drain();
        assertThat(rows("L")).hasSize(3).allSatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.PRICE_DROP);
            assertThat(notification.getPriceDrop().listingId()).isEqualTo("L");
            assertThat(notification.getPriceDrop().instant()).isEqualTo(Instant.parse("2026-10-05T10:15:30.123Z"));
            assertThat(notification.getSnapshot()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                    "listingTitle", "Sony WH-1000XM4", "oldPrice", "110.00", "newPrice", "95.00"));
        });
        assertThat(rows("L")).extracting(Notification::getRecipientSub).containsExactlyInAnyOrder("alice", "bob", "carol");
        assertThat(rows("other")).isEmpty();
        var pushedIds = new ArrayList<String>();
        while (pushedIds.size() < 3) {
            String raw = messages.poll(5, TimeUnit.SECONDS);
            assertThat(raw).as("notification pushed through Redis").isNotNull();
            var envelope = mapper.readTree(raw);
            if (RealtimePublisher.NOTIFICATIONS.equals(envelope.path("destination").asText())) {
                var payload = mapper.readTree(envelope.path("payload").asText());
                if ("L".equals(payload.path("target").path("listingId").asText())) {
                    Notification stored = mongo.findById(payload.path("id").asText(), Notification.class);
                    assertThat(stored).isNotNull();
                    assertThat(envelope.path("userSub").asText()).isEqualTo(stored.getRecipientSub());
                    pushedIds.add(stored.getId());
                }
            }
        }
        assertThat(pushedIds).containsExactlyInAnyOrderElementsOf(rows("L").stream().map(Notification::getId).toList());
        publish(json);
        drain();
        assertThat(rows("L")).hasSize(3);
        assertThat(admin.getQueueInfo(RabbitConfig.PRICE_DROP_DLQ).getMessageCount()).isZero();
    }

    @Test
    void equalScaledPricesIncreaseAndNonPriceChangesNotifyNobody() {
        publish(event("L", "10.0", "10.00", TIME));
        publish(event("L", "10.00", "10.01", TIME));
        publish(event("L", null, null, TIME));
        publish(event("L", null, "9.99", TIME));
        publish(event("L", "10.00", null, TIME));
        drain();
        assertThat(rows("L")).isEmpty();
    }

    @Test
    void oneCentDropNotifiesEveryActiveFavouriter() {
        publish(event("L", "10.00", "9.99", TIME));
        drain();
        assertThat(rows("L")).hasSize(3);
    }

    @Test
    void keysetPagingNotifiesAll1200FavouritersAndRedeliveryKeepsThemUnique() {
        List<FavouriteProjection> favourites = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            favourites.add(favourite("M", "user-%04d".formatted(i), true));
        }
        mongo.insert(favourites, FavouriteProjection.class);
        String json = event("M", "110.00", "95.00", TIME);
        publish(json);
        drain();
        assertThat(rows("M")).hasSize(1200);
        assertThat(rows("M").stream().map(Notification::getRecipientSub).distinct()).hasSize(1200);
        publish(json);
        drain();
        assertThat(rows("M")).hasSize(1200);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not json", "{}", "null", "{\"listingId\":\" \",\"occurredAt\":\"2026-10-05T10:15:30Z\"}",
            "{\"listingId\":\"L\",\"occurredAt\":null}"})
    void invalidEventsAreDeadLettered(String json) {
        publish(json == null ? "{\"listingId\":\"L\",\"occurredAt\":\"invalid\"}" : json);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(admin.getQueueInfo(RabbitConfig.PRICE_DROP_DLQ).getMessageCount()).isEqualTo(1));
        assertThat(rows("L")).isEmpty();
    }

    private List<Notification> rows(String listing) {
        return mongo.find(Query.query(Criteria.where("priceDrop.listingId").is(listing)), Notification.class);
    }

    private void seed(String listing, String sub, boolean active) {
        mongo.insert(favourite(listing, sub, active));
    }

    private FavouriteProjection favourite(String listing, String sub, boolean active) {
        return new FavouriteProjection(sub + "|" + listing, sub, listing, "seller", active,
                TIME, TIME.toString(), "seed", 1L);
    }

    private String event(String listing, String oldPrice, String newPrice, Instant time) {
        return "{\"listingId\":\"" + listing + "\",\"seq\":1,\"occurredAt\":\"" + time
                + "\",\"oldPrice\":" + oldPrice + ",\"newPrice\":" + newPrice
                + ",\"listingTitle\":\"Sony WH-1000XM4\"}";
    }

    private void publish(String json) {
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.LISTING_UPDATED, json);
    }

    private void drain() {
        // A marker on the same serial consumer proves previous no-op and replay deliveries finished.
        String marker = UUID.randomUUID().toString();
        seed(marker, marker, true);
        publish(event(marker, "2.00", "1.00", TIME));
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertThat(rows(marker)).hasSize(1));
        mongo.remove(Query.query(Criteria.where("priceDrop.listingId").is(marker)), Notification.class);
        mongo.remove(Query.query(Criteria.where("listingId").is(marker)), FavouriteProjection.class);
    }

    private LinkedBlockingQueue<String> subscribe() throws Exception {
        var messages = new LinkedBlockingQueue<String>();
        subscriber = new RedisMessageListenerContainer();
        subscriber.setConnectionFactory(redisConnectionFactory);
        subscriber.addMessageListener((message, pattern) -> messages.offer(
                new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)),
                ChannelTopic.of("rebyte:messaging:push"));
        subscriber.afterPropertiesSet();
        subscriber.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            publisher.push("probe", RealtimePublisher.PRESENCE, java.util.Map.of("ready", true));
            if (messages.poll(100, TimeUnit.MILLISECONDS) != null) {
                return messages;
            }
        }
        throw new AssertionError("Redis subscriber did not receive readiness probe");
    }
}
