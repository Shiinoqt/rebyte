package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.FavouriteProjection;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.messaging.FavouriteEvent;
import it.rebyte.messaging.messaging.FavouriteListener;
import it.rebyte.messaging.repository.FavouriteProjectionRepository;
import it.rebyte.messaging.repository.NotificationRepository;
import it.rebyte.messaging.service.FavouriteNotificationService;
import it.rebyte.messaging.support.MessagingInfraTestBase;
import it.rebyte.messaging.support.StubHttpServer;

@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.concurrency=1",
        "spring.rabbitmq.listener.simple.max-concurrency=1",
        "rebyte.outbox.relay.fixed-delay-ms=3600000"
})
class FavouriteNotificationIT extends MessagingInfraTestBase {

    private static final StubHttpServer SERVER = new StubHttpServer();
    private static final Instant TIME = Instant.parse("2026-10-05T10:00:00.123456789Z");
    private static final String ACTOR = "favouriter";
    private static final String RECIPIENT = "seller";
    private static final String LISTING = "listing-1";

    @DynamicPropertySource
    static void userServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.user-service.url", SERVER::baseUrl);
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean MongoTemplate mongo;
    @Autowired FavouriteProjectionRepository states;
    @Autowired NotificationRepository notifications;
    @Autowired FavouriteNotificationService service;
    @Autowired FavouriteListener listener;

    @BeforeEach
    void clean() {
        drain();
        notifications.deleteAll();
        states.deleteAll();
        admin.purgeQueue(RabbitConfig.FAVOURITE_DLQ, false);
        SERVER.clearRequests();
        SERVER.respond("/internal/users/" + ACTOR, 200, "{\"sub\":\"favouriter\",\"username\":\"Alice\"}");
    }

    @AfterEach
    void finishDeliveries() {
        drain();
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void favouriteCreatesSnapshotOnceAndUnfavouriteDeletesReadNotificationIdempotently() throws Exception {
        FavouriteEvent create = event("create", TIME, null, true);
        sendAndDrain(create, true);
        Notification stored = onlyNotification();
        assertThat(stored.getType()).isEqualTo(NotificationType.FAVOURITE);
        assertThat(stored.getRecipientSub()).isEqualTo(RECIPIENT);
        assertThat(stored.getFavourite().favouriterSub()).isEqualTo(ACTOR);
        assertThat(stored.getDedupKey()).isEqualTo("FAVOURITE:favouriter:listing-1");
        assertThat(stored.getSnapshot()).containsEntry("actorUsername", "Alice").containsEntry("listingTitle", "Keyboard");
        assertThat(stored.getFavourite().listingId()).isEqualTo(LISTING);
        assertThat(state().getSellerSub()).isEqualTo(RECIPIENT);
        assertThat(state().isActive()).isTrue();
        assertThat(SERVER.requests()).hasSize(1);

        sendAndDrain(create, true);
        assertThat(onlyNotification().getId()).isEqualTo(stored.getId());
        stored.markRead(Instant.now(), Duration.ofDays(30));
        notifications.save(stored);
        FavouriteEvent remove = event("remove", TIME.plusSeconds(1), null, false);
        sendAndDrain(remove, false);
        assertEmptyAndInactive("remove");
        sendAndDrain(remove, false);
        assertEmptyAndInactive("remove");
    }

    @Test
    void unfavouriteFirstRejectsOlderFavouriteAndAllowsLaterFavourite() throws Exception {
        sendAndDrain(event("remove", TIME.plusSeconds(1), null, false), false);
        sendAndDrain(event("older", TIME, null, true), true);
        assertEmptyAndInactive("remove");
        sendAndDrain(event("later", TIME.plusSeconds(2), null, true), true);
        assertThat(onlyNotification()).isNotNull();
        assertThat(state().isActive()).isTrue();
    }

    @Test
    void nanosecondLegacyTimesSurviveMongoAndOrderWithinOneMillisecond() throws Exception {
        FavouriteEvent create = event("nano-create", TIME, null, true);
        sendAndDrain(create, true);
        assertThat(state().getLastEventTime()).isEqualTo("2026-10-05T10:00:00.123456789Z");
        String id = onlyNotification().getId();
        sendAndDrain(create, true);
        assertThat(onlyNotification().getId()).isEqualTo(id);
        sendAndDrain(event("nano-remove", TIME.plusNanos(1), null, false), false);
        sendAndDrain(create, true);
        assertEmptyAndInactive("nano-remove");
        assertThat(state().getLastEventTime()).isEqualTo("2026-10-05T10:00:00.123456790Z");
    }

    @Test
    void legacyExactTimeRemovalWinsInBothDeliveryOrders() throws Exception {
        FavouriteEvent create = event("z-create", TIME, null, true);
        FavouriteEvent remove = event("a-remove", TIME, null, false);
        sendAndDrain(create, true);
        sendAndDrain(remove, false);
        sendAndDrain(create, true);
        assertEmptyAndInactive("a-remove");
        states.deleteAll();
        sendAndDrain(remove, false);
        sendAndDrain(create, true);
        assertEmptyAndInactive("a-remove");
    }

    @Test
    void sameInstantSequencesCreateRemoveRecreateAndIgnoreEveryReplayAndLowerSequence() throws Exception {
        FavouriteEvent first = event("first", TIME, 1L, true);
        FavouriteEvent remove = event("remove", TIME, 2L, false);
        FavouriteEvent recreate = event("recreate", TIME, 3L, true);
        sendAndDrain(first, true);
        String originalId = onlyNotification().getId();
        sendAndDrain(first, true);
        sendAndDrain(remove, false);
        assertEmptyAndInactive("remove");
        sendAndDrain(remove, false);
        sendAndDrain(recreate, true);
        String recreatedId = onlyNotification().getId();
        assertThat(recreatedId).isNotEqualTo(originalId);
        sendAndDrain(recreate, true);
        sendAndDrain(first, true);
        sendAndDrain(remove, false);
        sendAndDrain(event("late-stale", TIME.plusSeconds(10), 2L, false), false);
        sendAndDrain(event("old-legacy", TIME.minusNanos(1), null, false), false);
        assertThat(onlyNotification().getId()).isEqualTo(recreatedId);
        assertThat(state().isActive()).isTrue();
        assertThat(state().getLastRelationshipSequence()).isEqualTo(3L);
        assertThat(state().getLastEventId()).isEqualTo("recreate");
    }

    @Test
    void reverseSequencesKeepRemovalUntilHigherSequenceRecreates() throws Exception {
        sendAndDrain(event("remove", TIME, 2L, false), false);
        sendAndDrain(event("first", TIME, 1L, true), true);
        assertEmptyAndInactive("remove");
        sendAndDrain(event("recreate", TIME, 3L, true), true);
        assertThat(state().isActive()).isTrue();
        assertThat(onlyNotification()).isNotNull();
    }

    @Test
    void sequenceConflictIsDeadLetteredWithoutChangingState() throws Exception {
        sendAndDrain(event("create", TIME, 1L, true), true);
        sendAndDrain(event("conflict", TIME, 1L, false), false);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(depth(RabbitConfig.FAVOURITE_DLQ)).isEqualTo(1));
        assertThat(state().getLastEventId()).isEqualTo("create");
        assertThat(state().isActive()).isTrue();
        assertThat(onlyNotification()).isNotNull();
    }

    @Test
    void usernameFailureDoesNotPersistAnAnonymousNotificationAndRetryResolvesName() throws Exception {
        var create = event("create", TIME, null, true);
        SERVER.respond("/internal/users/" + ACTOR, 503, "{}");
        assertThatThrownBy(() -> service.favourited(create))
                .isInstanceOf(it.rebyte.messaging.error.UserServiceUnavailableException.class);
        assertThat(notifications.count()).isZero();
        assertThat(states.count()).isZero();
        SERVER.respond("/internal/users/" + ACTOR, 200,
                "{\"sub\":\"" + ACTOR + "\",\"username\":\"Alice\"}");
        sendAndDrain(create, true);
        assertThat(onlyNotification().getSnapshot()).containsEntry("actorUsername", "Alice");
    }

    @Test
    void missingUserStillCreatesNotificationWithoutUsername() throws Exception {
        SERVER.respond("/internal/users/" + ACTOR, 404, "{}");
        sendAndDrain(event("create", TIME, null, true), true);
        assertThat(onlyNotification().getSnapshot()).doesNotContainKey("actorUsername");
    }

    @Test
    void concurrentTransitionsRereadGuardAfterWriteConflict() throws Exception {
        service.favourited(event("first", TIME, 1L, true));
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (reads.incrementAndGet() <= 2) {
                barrier.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(mongo).findById(ACTOR + "|" + LISTING, FavouriteProjection.class);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var removal = executor.submit(() -> {
                service.unfavourited(event("remove", TIME, 2L, false));
                return null;
            });
            var recreation = executor.submit(() -> {
                service.favourited(event("recreate", TIME, 3L, true));
                return null;
            });
            removal.get(15, TimeUnit.SECONDS);
            recreation.get(15, TimeUnit.SECONDS);
        }
        assertThat(reads.get()).as("a conflicting transaction must reread the guard").isGreaterThanOrEqualTo(3);
        assertThat(state().getLastRelationshipSequence()).isEqualTo(3L);
        assertThat(state().isActive()).isTrue();
        assertThat(onlyNotification()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloads")
    void invalidEventIsDeadLetteredWithoutStateOrNotification(String scenario, String body) {
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.LISTING_FAVOURITED, body);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(depth(RabbitConfig.FAVOURITE_DLQ)).isEqualTo(1));
        assertThat(notifications.count()).isZero();
        assertThat(states.count()).isZero();
    }

    @Test
    void newerFavouriteThenIntermediateRemovalLeavesActiveProjectionAndOneNotification() throws Exception {
        sendAndDrain(event("first", TIME, null, true), true);
        String id = onlyNotification().getId();
        sendAndDrain(event("latest", TIME.plusSeconds(2), null, true), true);
        sendAndDrain(event("middle-removal", TIME.plusSeconds(1), null, false), false);
        assertThat(state().isActive()).isTrue();
        assertThat(state().getLastEventId()).isEqualTo("latest");
        assertThat(onlyNotification().getId()).isEqualTo(id);
    }

    @Test
    void projectionHasListingActiveUserIndexForPriceDropFanOut() {
        assertThat(mongo.indexOps(FavouriteProjection.class).getIndexInfo()).anySatisfy(index ->
                assertThat(index.getIndexFields()).extracting(field -> field.getKey())
                        .containsExactly("listingId", "active", "userSub"));
    }

    @Test
    void listingRemovalDoesNotChangeProjectionOrExistingNotification() throws Exception {
        sendAndDrain(event("create", TIME, 1L, true), true);
        String id = onlyNotification().getId();
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.LISTING_REMOVED,
                mapper.writeValueAsString(Map.of("listingId", LISTING, "occurredAt", TIME.plusSeconds(1).toString())));
        drain();
        assertThat(onlyNotification().getId()).isEqualTo(id);
        assertThat(state().isActive()).isTrue();
        assertThat(state().getLastEventId()).isEqualTo("create");
    }

    @Test
    void missingTitleIsAcceptedForWithdrawnOrLegacyFavourite() throws Exception {
        FavouriteEvent create = new FavouriteEvent("legacy", ACTOR, LISTING, RECIPIENT, null, TIME, null);
        sendAndDrain(create, true);
        assertThat(onlyNotification().getSnapshot()).containsEntry("actorUsername", "Alice")
                .doesNotContainKey("listingTitle");
        sendAndDrain(new FavouriteEvent("remove", ACTOR, LISTING, RECIPIENT, null, TIME.plusNanos(1), null), false);
        assertEmptyAndInactive("remove");
    }

    @Test
    void unknownRoutingKeyIsRejected() throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey("unknown");
        Message message = new Message(mapper.writeValueAsBytes(event("unknown", TIME, null, true)), properties);
        assertThatThrownBy(() -> listener.onEvent(message)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(notifications.count()).isZero();
    }

    static Stream<Arguments> invalidPayloads() throws Exception {
        var cases = Stream.<Arguments>builder();
        cases.add(Arguments.of("malformed JSON", "not-json"));
        cases.add(Arguments.of("JSON null", "null"));
        for (String field : List.of("eventId", "userSub", "listingId", "sellerSub")) {
            for (Object value : new Object[] {null, " ", "x".repeat(65), "non-ascii-é"}) {
                cases.add(Arguments.of(field + "=" + value, invalid(field, value)));
            }
        }
        cases.add(Arguments.of("self favourite", invalid("sellerSub", ACTOR)));
        cases.add(Arguments.of("missing time", invalid("occurredAt", null)));
        cases.add(Arguments.of("bad time", invalid("occurredAt", "yesterday")));
        cases.add(Arguments.of("fractional sequence", invalid("relationshipSequence", 1.5)));
        cases.add(Arguments.of("zero sequence", invalid("relationshipSequence", 0)));
        cases.add(Arguments.of("negative sequence", invalid("relationshipSequence", -1)));
        return cases.build();
    }

    private static String invalid(String field, Object value) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("eventId", "invalid", "userSub", ACTOR,
                "sellerSub", RECIPIENT, "listingId", LISTING, "occurredAt", TIME.toString()));
        payload.put(field, value);
        return new ObjectMapper().writeValueAsString(payload);
    }

    private FavouriteEvent event(String id, Instant time, Long sequence, boolean active) {
        return new FavouriteEvent(id, ACTOR, LISTING, RECIPIENT, "Keyboard", time, sequence);
    }

    private void sendAndDrain(FavouriteEvent event, boolean active) throws Exception {
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS,
                active ? RebyteExchanges.LISTING_FAVOURITED : RebyteExchanges.LISTING_UNFAVOURITED,
                payload(event));
        drain();
    }

    private String payload(FavouriteEvent event) throws Exception {
        var json = mapper.valueToTree(event);
        if (event.relationshipSequence() == null) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("relationshipSequence");
        }
        return mapper.writeValueAsString(json);
    }

    @Test
    void newerLegacyRemovalPreservesSequenceHighWaterMark() throws Exception {
        sendAndDrain(event("sequenced", TIME, 3L, true), true);
        sendAndDrain(event("legacy-remove", TIME.plusNanos(1), null, false), false);
        assertEmptyAndInactive("legacy-remove");
        assertThat(state().getLastRelationshipSequence()).isEqualTo(3L);
        sendAndDrain(event("old-sequence", TIME.plusSeconds(10), 2L, true), true);
        assertEmptyAndInactive("legacy-remove");
        sendAndDrain(event("new-sequence", TIME, 4L, true), true);
        assertThat(state().isActive()).isTrue();
        assertThat(state().getLastRelationshipSequence()).isEqualTo(4L);
        assertThat(onlyNotification()).isNotNull();
    }

    private void drain() {
        // A marker on the same single-consumer queue proves prior deliveries finished,
        // including replays and stale events which deliberately leave no state change.
        String marker = UUID.randomUUID().toString();
        FavouriteEvent event = new FavouriteEvent(marker, marker, "drain", RECIPIENT, null, TIME, null);
        try {
            rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.LISTING_UNFAVOURITED,
                    mapper.writeValueAsString(event));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(states.findById(marker + "|drain")).isPresent();
            assertThat(depth(RabbitConfig.FAVOURITE_QUEUE)).isZero();
        });
        states.deleteById(marker + "|drain");
    }

    private long depth(String queue) {
        var info = admin.getQueueInfo(queue);
        assertThat(info).isNotNull();
        return info.getMessageCount();
    }

    private FavouriteProjection state() {
        return states.findById(ACTOR + "|" + LISTING).orElseThrow();
    }

    private Notification onlyNotification() {
        List<Notification> stored = mongo.find(Query.query(Criteria.where("recipientSub").is(RECIPIENT)
                .and("type").is(NotificationType.FAVOURITE)), Notification.class);
        assertThat(stored).hasSize(1);
        return stored.getFirst();
    }

    private void assertEmptyAndInactive(String eventId) {
        assertThat(notifications.count()).isZero();
        assertThat(state().isActive()).isFalse();
        assertThat(state().getLastEventId()).isEqualTo(eventId);
    }
}
