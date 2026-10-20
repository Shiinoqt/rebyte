package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.dto.NotificationView;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.support.MessagingInfraTestBase;
import it.rebyte.messaging.support.StubHttpServer;

@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = {
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "spring.rabbitmq.listener.simple.concurrency=1",
        "spring.rabbitmq.listener.simple.max-concurrency=1"
})
class VersionReleaseFanOutIT extends MessagingInfraTestBase {

    private static final StubHttpServer ORDERS = new StubHttpServer();
    private static final StubHttpServer USERS = new StubHttpServer();
    private static final String LISTING = "software-L";
    private static final String SELLER = "seller";
    private static final String VERSION = "2.1.0";
    private static final List<String> HOLDERS = IntStream.range(0, 150).mapToObj(i -> "holder-%03d".formatted(i)).toList();
    private static final List<String> FOLLOWERS = followers();
    private static final String HOLDERS_FIRST = "/internal/licences/holders?listingId=" + LISTING + "&size=100";
    private static final String HOLDERS_SECOND = "/internal/licences/holders?listingId=" + LISTING + "&cursor=holder-099&size=100";
    private static final String FOLLOWERS_FIRST = "/internal/users/seller/followers?limit=500";
    private static final String FOLLOWERS_SECOND = "/internal/users/seller/followers?cursor=follower-499&limit=500";

    @DynamicPropertySource
    static void clients(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.order-service.url", ORDERS::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.user-service.url", USERS::baseUrl);
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired RealtimePublisher publisher;
    private RedisMessageListenerContainer subscriber;

    @BeforeEach
    void clean() throws Exception {
        drain();
        mongo.remove(new Query(), Notification.class);
        mongo.remove(new Query(), OutboxEvent.class);
        admin.purgeQueue(RabbitConfig.VERSION_RELEASED_DLQ, false);
        ORDERS.clearRequests();
        USERS.clearRequests();
        ORDERS.respond(HOLDERS_FIRST, 200, page("buyerSubs", HOLDERS.subList(0, 100), "holder-099"));
        ORDERS.respond(HOLDERS_SECOND, 200, page("buyerSubs", HOLDERS.subList(100, 150), null));
        USERS.respond(FOLLOWERS_FIRST, 200, page("subs", FOLLOWERS.subList(0, 500), "follower-499"));
        USERS.respond(FOLLOWERS_SECOND, 200, page("subs", FOLLOWERS.subList(500, 600), null));
    }

    @AfterEach
    void finishDeliveries() throws Exception {
        drain();
        if (subscriber != null) {
            subscriber.stop();
            subscriber.destroy();
        }
    }

    @AfterAll
    static void stopServers() {
        ORDERS.close();
        USERS.close();
    }

    @Test
    void pagesHoldersAndFollowersAndNotifiesAndEmailsThe709RecipientUnionOnce() throws Exception {
        var messages = subscribe();
        publish(LISTING, SELLER, VERSION);
        drain();
        assertComplete();
        assertPushed(messages);
        assertThat(ORDERS.requests().stream().filter(request -> request.query().contains("listingId=" + LISTING)))
                .hasSize(2).allSatisfy(request -> assertThat(request.query()).contains("size=100"));
        assertThat(USERS.requests().stream().filter(request -> request.path().equals("/internal/users/seller/followers")))
                .hasSize(2).allSatisfy(request -> assertThat(request.query()).contains("limit=500"));
        Set<String> notificationIds = new HashSet<>(rows().stream().map(Notification::getId).toList());
        Set<String> emailIds = new HashSet<>(jobs().stream().map(EmailJob::eventId).toList());
        publish(LISTING, SELLER, VERSION);
        drain();
        assertComplete();
        assertThat(rows().stream().map(Notification::getId)).containsExactlyInAnyOrderElementsOf(notificationIds);
        assertThat(jobs().stream().map(EmailJob::eventId)).containsExactlyInAnyOrderElementsOf(emailIds);
        assertThat(depth(RabbitConfig.VERSION_RELEASED_DLQ)).isZero();
    }

    @Test
    void transientSecondFollowerPageFailureRetriesFromTheStartWithoutDuplicates() throws Exception {
        USERS.respondOnce(FOLLOWERS_SECOND, 503, "{}");
        publish(LISTING, SELLER, VERSION);
        drain();
        assertComplete();
        assertThat(ORDERS.requests().stream().filter(request -> request.query().contains("listingId=" + LISTING)
                && !request.query().contains("cursor="))).hasSize(2);
        assertThat(USERS.requests().stream().filter(request -> request.query() != null
                && request.query().contains("cursor=follower-499"))).hasSize(2);
        assertThat(depth(RabbitConfig.VERSION_RELEASED_DLQ)).isZero();
    }

    @Test
    void persistentFailureDeadLettersAfterConfiguredAttemptsAndReplayCompletesTheFanOut() throws Exception {
        USERS.respond(FOLLOWERS_SECOND, 503, "{}");
        publish(LISTING, SELLER, VERSION);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(depth(RabbitConfig.VERSION_RELEASED_DLQ)).isEqualTo(1));
        assertThat(rows()).hasSize(650);
        assertThat(rows().stream().map(Notification::getRecipientSub)).containsAll(HOLDERS).doesNotContain(SELLER);
        assertThat(jobs()).hasSize(650);
        assertThat(USERS.requests().stream().filter(request -> request.query() != null
                && request.query().contains("cursor=follower-499"))).hasSize(4);
        USERS.respond(FOLLOWERS_SECOND, 200, page("subs", FOLLOWERS.subList(500, 600), null));
        publish(LISTING, SELLER, VERSION);
        drain();
        assertComplete();
        assertThat(depth(RabbitConfig.VERSION_RELEASED_DLQ)).isEqualTo(1);
    }

    @Test
    void replayRepairsMissingEmailJobsForAnAlreadyInsertedNotificationPage() throws Exception {
        publish(LISTING, SELLER, VERSION);
        drain();
        assertComplete();
        List<OutboxEvent> firstPage = mongo.find(new Query(), OutboxEvent.class).stream()
                .filter(row -> HOLDERS.subList(0, 100).contains(readJob(row).recipientSub())).toList();
        assertThat(firstPage).hasSize(100);
        mongo.remove(Query.query(Criteria.where("eventId").in(firstPage.stream().map(OutboxEvent::getEventId).toList())),
                OutboxEvent.class);
        assertThat(jobs()).hasSize(609);
        publish(LISTING, SELLER, VERSION);
        drain();
        assertComplete();
    }

    @Test
    void invalidReleasePayloadsAreDeadLetteredWithoutHttpCallsOrWrites() {
        for (String json : List.of("not json", "null", "{}",
                "{\"listingId\":\" \",\"sellerSub\":\"seller\",\"versionNumber\":\"2.1.0\"}",
                "{\"listingId\":\"software-L\",\"sellerSub\":\" \",\"versionNumber\":\"2.1.0\"}",
                "{\"listingId\":\"software-L\",\"sellerSub\":\"seller\",\"versionNumber\":\" \"}")) {
            rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.LISTING_VERSION_RELEASED, json);
        }
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(depth(RabbitConfig.VERSION_RELEASED_DLQ)).isEqualTo(6));
        assertThat(rows()).isEmpty();
        assertThat(jobs()).isEmpty();
        assertThat(ORDERS.requests()).isEmpty();
        assertThat(USERS.requests()).isEmpty();
    }

    @Test
    void emptyPagesAndBlankRecipientsExcludeTheSellerWithoutBlockingPagination() throws Exception {
        ORDERS.respond(HOLDERS_FIRST, 200, page("buyerSubs", List.of("", " ", SELLER), "holder-099"));
        ORDERS.respond(HOLDERS_SECOND, 200, page("buyerSubs", List.of("alice", "alice"), null));
        USERS.respond(FOLLOWERS_FIRST, 200, page("subs", List.of(), "follower-499"));
        USERS.respond(FOLLOWERS_SECOND, 200, page("subs", List.of("alice", "bob", SELLER, " "), null));
        publish(LISTING, SELLER, VERSION);
        drain();
        assertThat(rows()).extracting(Notification::getRecipientSub).containsExactlyInAnyOrder("alice", "bob");
        assertThat(jobs()).extracting(EmailJob::recipientSub).containsExactlyInAnyOrder("alice", "bob");
        assertThat(depth(RabbitConfig.VERSION_RELEASED_DLQ)).isZero();
    }

    private void assertComplete() {
        Set<String> expected = new HashSet<>(HOLDERS);
        expected.addAll(FOLLOWERS);
        expected.remove(SELLER);
        assertThat(expected).hasSize(709);
        List<Notification> notifications = rows();
        assertThat(notifications).hasSize(709);
        assertThat(notifications.stream().map(Notification::getRecipientSub)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(notifications).allSatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.RELEASE);
            assertThat(notification.getRelease().listingId()).isEqualTo(LISTING);
            assertThat(notification.getRelease().versionNumber()).isEqualTo(VERSION);
            assertThat(notification.getDedupKey()).isEqualTo("RELEASE:software-L:2.1.0");
            assertThat(notification.getSnapshot()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "listingTitle", "Editor", "versionNumber", VERSION));
            assertThat(NotificationView.from(notification).target()).containsEntry("versionNumber", VERSION);
        });
        List<EmailJob> emails = jobs();
        assertThat(emails).hasSize(709);
        assertThat(emails.stream().map(EmailJob::recipientSub)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(emails.stream().map(EmailJob::eventId).distinct()).hasSize(709);
        assertThat(emails).allSatisfy(job -> {
            assertThat(job.kind()).isEqualTo(EmailKind.NEW_VERSION);
            assertThat(job.dedupKey()).isEqualTo("RELEASE:software-L:2.1.0");
            assertThat(job.data()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "listingId", LISTING, "listingTitle", "Editor", "versionNumber", VERSION));
        });
    }

    private List<Notification> rows() {
        return mongo.find(Query.query(Criteria.where("release.listingId").is(LISTING)), Notification.class);
    }

    private List<EmailJob> jobs() {
        return mongo.find(Query.query(Criteria.where("routingKey").is(RabbitConfig.EMAIL_SEND_ROUTING_KEY)), OutboxEvent.class)
                .stream().map(this::readJob).filter(job -> LISTING.equals(job.data().get("listingId"))).toList();
    }

    private EmailJob readJob(OutboxEvent row) {
        try {
            return mapper.readValue(row.getPayload(), EmailJob.class);
        } catch (Exception failure) {
            throw new AssertionError("Invalid email job in outbox", failure);
        }
    }

    private void publish(String listing, String seller, String version) throws Exception {
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.LISTING_VERSION_RELEASED,
                mapper.writeValueAsString(Map.of("listingId", listing, "sellerSub", seller,
                        "versionNumber", version, "listingTitle", "Editor")));
    }

    private String page(String field, List<String> subs, String cursor) throws Exception {
        Map<String, Object> page = new java.util.HashMap<>();
        page.put(field, subs);
        page.put("nextCursor", cursor);
        return mapper.writeValueAsString(page);
    }

    private void drain() throws Exception {
        // A marker on the serial release consumer waits for all earlier retries and duplicate deliveries.
        String marker = UUID.randomUUID().toString();
        ORDERS.respond("/internal/licences/holders?listingId=" + marker + "&size=100", 200,
                page("buyerSubs", List.of(marker), null));
        USERS.respond("/internal/users/" + marker + "-seller/followers?limit=500", 200, page("subs", List.of(), null));
        publish(marker, marker + "-seller", "1.0.0");
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                assertThat(USERS.requests()).anySatisfy(request ->
                        assertThat(request.path()).isEqualTo("/internal/users/" + marker + "-seller/followers")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mongo.count(Query.query(Criteria.where("release.listingId").is(marker)), Notification.class)).isEqualTo(1));
        mongo.remove(Query.query(Criteria.where("release.listingId").is(marker)), Notification.class);
        List<String> markerJobs = mongo.find(new Query(), OutboxEvent.class).stream()
                .filter(row -> marker.equals(readJob(row).data().get("listingId"))).map(OutboxEvent::getEventId).toList();
        mongo.remove(Query.query(Criteria.where("eventId").in(markerJobs)), OutboxEvent.class);
    }

    private LinkedBlockingQueue<String> subscribe() throws Exception {
        var messages = new LinkedBlockingQueue<String>();
        subscriber = new RedisMessageListenerContainer();
        subscriber.setConnectionFactory(redisConnectionFactory);
        subscriber.addMessageListener((message, pattern) -> messages.offer(
                new String(message.getBody(), StandardCharsets.UTF_8)), ChannelTopic.of("rebyte:messaging:push"));
        subscriber.afterPropertiesSet();
        subscriber.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            publisher.push("probe", RealtimePublisher.PRESENCE, Map.of("ready", true));
            if (messages.poll(100, TimeUnit.MILLISECONDS) != null) {
                return messages;
            }
        }
        throw new AssertionError("Redis subscriber did not receive readiness probe");
    }

    private void assertPushed(LinkedBlockingQueue<String> messages) throws Exception {
        List<String> pushedIds = new ArrayList<>();
        while (pushedIds.size() < 709) {
            String raw = messages.poll(5, TimeUnit.SECONDS);
            assertThat(raw).as("release notification pushed through Redis").isNotNull();
            var envelope = mapper.readTree(raw);
            if (RealtimePublisher.NOTIFICATIONS.equals(envelope.path("destination").asText())) {
                var payload = mapper.readTree(envelope.path("payload").asText());
                if (LISTING.equals(payload.path("target").path("listingId").asText())) {
                    Notification stored = mongo.findById(payload.path("id").asText(), Notification.class);
                    assertThat(stored).isNotNull();
                    assertThat(envelope.path("userSub").asText()).isEqualTo(stored.getRecipientSub());
                    pushedIds.add(stored.getId());
                }
            }
        }
        assertThat(pushedIds).containsExactlyInAnyOrderElementsOf(rows().stream().map(Notification::getId).toList());
    }

    private long depth(String queue) {
        return admin.getQueueInfo(queue).getMessageCount();
    }

    private static List<String> followers() {
        List<String> subs = new ArrayList<>(HOLDERS.subList(0, 40));
        subs.add(SELLER);
        IntStream.range(0, 559).mapToObj(i -> "follower-%03d".formatted(i)).forEach(subs::add);
        subs.sort(String::compareTo);
        return List.copyOf(subs);
    }
}
