package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.dto.ConversationView;
import it.rebyte.messaging.dto.MessagePage;
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.service.ReadService;
import it.rebyte.messaging.service.UnreadSummaryService;
import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.StubHttpServer;
import it.rebyte.messaging.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
@TestPropertySource(properties = {
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *"
})
class UnreadCountersIT extends MessagingTestBase {

    private static final StubHttpServer CATALOG = new StubHttpServer();
    private static final StubHttpServer USERS = new StubHttpServer();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", CATALOG::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.user-service.url", USERS::baseUrl);
    }

    @AfterAll
    static void stopServers() {
        CATALOG.close();
        USERS.close();
    }

    @Autowired TestRestTemplate rest;
    @Autowired MongoTemplate mongo;
    @Autowired ReadService reads;
    @Autowired UnreadSummaryService unreadSummary;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired RealtimePublisher publisher;
    @Autowired ObjectMapper objectMapper;

    @Value("${rebyte.realtime.push-channel}")
    String channel;

    private RedisMessageListenerContainer listener;
    private final LinkedBlockingQueue<String> pushes = new LinkedBlockingQueue<>();

    @AfterEach
    void stopListener() throws Exception {
        if (listener != null) {
            listener.stop();
            listener.destroy();
            listener = null;
        }
    }

    @Test
    void newestPageReadClearsCountersAndNotificationsAndPushesOneUnreadSummary() throws Exception {
        Chat chat = startChat("1");
        send(chat.buyer(), chat.id(), "2");
        send(chat.buyer(), chat.id(), "3");
        assertThat(conversation(chat).unreadFor(chat.seller())).isEqualTo(3);
        assertThat(unreadSummary.summary(chat.seller()).messages()).isEqualTo(3);
        assertThat(unreadSummary.summary(chat.seller()).notifications()).isEqualTo(1);
        startListenerAndWaitForSubscription();

        history(chat.seller(), chat.id(), null);

        assertThat(conversation(chat).unreadFor(chat.seller())).isZero();
        List<Notification> notifications = messageNotifications(chat.seller(), chat.id());
        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.isRead()).isTrue();
            assertThat(notification.getExpiresAt()).isEqualTo(notification.getReadAt().plus(Duration.ofDays(30)));
        });
        assertThat(unreadPushesFor(chat.seller())).isEqualTo(1);
    }

    @Test
    void messageArrivingAfterThePageWasFetchedStaysUnread() {
        Chat chat = startChat("m1");
        send(chat.buyer(), chat.id(), "m2");
        send(chat.buyer(), chat.id(), "m3");
        send(chat.buyer(), chat.id(), "m4");
        send(chat.buyer(), chat.id(), "m5");
        List<Message> page = mongo.find(Query.query(Criteria.where("conversationId").is(chat.id()))
                .with(Sort.by(Sort.Direction.DESC, "seq")).limit(20), Message.class);
        long newest = ReadService.newestSeq(page);
        assertThat(newest).isEqualTo(5);
        String notificationId = messageNotifications(chat.seller(), chat.id()).getFirst().getId();

        MessageView m6 = send(chat.buyer(), chat.id(), "m6");
        reads.markRead(chat.seller(), conversation(chat), newest);

        assertThat(conversation(chat).unreadFor(chat.seller())).isEqualTo(1);
        assertThat(mongo.findById(m6.id(), Message.class).isRead()).isFalse();
        assertThat(messageNotifications(chat.seller(), chat.id()))
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.getId()).isEqualTo(notificationId);
                    assertThat(notification.getMessage().messageId()).isEqualTo(m6.id());
                    assertThat(notification.getMessage().seq()).isEqualTo(m6.seq());
                    assertThat(notification.isRead()).isFalse();
                    assertThat(notification.getExpiresAt()).isNull();
                });
    }

    @Test
    void historyWithACursorMarksNothing() {
        Chat chat = startChat("1");
        for (int i = 2; i <= 25; i++) {
            send(chat.buyer(), chat.id(), Integer.toString(i));
        }
        String cursor = olderCursor(chat.buyer(), chat.id());

        history(chat.seller(), chat.id(), cursor);

        assertThat(conversation(chat).unreadFor(chat.seller())).isEqualTo(25);
        assertThat(messageNotifications(chat.seller(), chat.id())).noneMatch(Notification::isRead);
    }

    @Test
    void readingWithNothingUnreadChangesNoCounterOrNotification() {
        Chat chat = startChat("1");
        history(chat.seller(), chat.id(), null);
        Conversation before = conversation(chat);
        List<Notification> notificationsBefore = messageNotifications(chat.seller(), chat.id());

        history(chat.seller(), chat.id(), null);
        history(chat.buyer(), chat.id(), null);

        Conversation after = conversation(chat);
        assertThat(after.getBuyerUnread()).isEqualTo(before.getBuyerUnread());
        assertThat(after.getSellerUnread()).isEqualTo(before.getSellerUnread());
        assertThat(messageNotifications(chat.seller(), chat.id()))
                .usingRecursiveFieldByFieldElementComparator().containsExactlyInAnyOrderElementsOf(notificationsBefore);
    }

    @Test
    void newestPageReadClearsTheReadersEmailStamp() {
        Chat chat = startChat("1");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(chat.id())),
                new Update().set("buyerLastEmailAt", Instant.now()), Conversation.class);

        history(chat.buyer(), chat.id(), null);

        assertThat(conversation(chat).getBuyerLastEmailAt()).isNull();
    }

    @Test
    void countersStayExactUnderRandomSendsAndReads() {
        String buyer = sub("buyer");
        String seller = sub("seller");
        List<Chat> chats = List.of(startChat(buyer, seller, "a"), startChat(buyer, seller, "b"));
        Random random = new Random(20261005L);

        for (int operation = 0; operation < 200; operation++) {
            Chat chat = chats.get(random.nextInt(chats.size()));
            switch (random.nextInt(4)) {
                case 0 -> send(buyer, chat.id(), "b" + operation);
                case 1 -> send(seller, chat.id(), "s" + operation);
                case 2 -> history(buyer, chat.id(), null);
                default -> history(seller, chat.id(), null);
            }
            long buyerTotal = 0;
            long sellerTotal = 0;
            for (Chat each : chats) {
                Conversation stored = conversation(each);
                long buyerUnread = unreadMessages(buyer, each.id());
                long sellerUnread = unreadMessages(seller, each.id());
                assertThat(stored.unreadFor(buyer)).as("buyer unread after operation %d", operation)
                        .isEqualTo(buyerUnread);
                assertThat(stored.unreadFor(seller)).as("seller unread after operation %d", operation)
                        .isEqualTo(sellerUnread);
                buyerTotal += buyerUnread;
                sellerTotal += sellerUnread;
            }
            assertThat(unreadSummary.summary(buyer).messages()).isEqualTo(buyerTotal);
            assertThat(unreadSummary.summary(seller).messages()).isEqualTo(sellerTotal);
        }
    }

    private long unreadMessages(String recipientSub, String conversationId) {
        return mongo.count(Query.query(Criteria.where("conversationId").is(conversationId)
                .and("recipientSub").is(recipientSub).and("read").is(false)), Message.class);
    }

    private List<Notification> messageNotifications(String recipientSub, String conversationId) {
        return mongo.find(Query.query(Criteria.where("recipientSub").is(recipientSub)
                .and("type").is(NotificationType.MESSAGE).and("message.conversationId").is(conversationId)),
                Notification.class);
    }

    private Conversation conversation(Chat chat) {
        return mongo.findById(chat.id(), Conversation.class);
    }

    private String olderCursor(String sub, String conversationId) {
        ResponseEntity<MessagePage> page = rest.exchange("/conversations/" + conversationId + "/messages?size=5",
                HttpMethod.GET, new HttpEntity<>(headers(sub)), MessagePage.class);
        assertThat(page.getBody().nextCursor()).isNotNull();
        return page.getBody().nextCursor();
    }

    private void history(String sub, String conversationId, String cursor) {
        String path = "/conversations/" + conversationId + "/messages" + (cursor == null ? "" : "?cursor=" + cursor);
        ResponseEntity<MessagePage> response = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(sub)),
                MessagePage.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Chat startChat(String text) {
        return startChat(sub("buyer"), sub("seller"), text);
    }

    private Chat startChat(String buyer, String seller, String text) {
        String listingId = UUID.randomUUID().toString();
        CATALOG.respond("/internal/listings/" + listingId, 200, "{\"id\":\"" + listingId + "\",\"sellerSub\":\""
                + seller + "\",\"listingTitle\":\"Oggetto\",\"price\":10.00,\"type\":\"HARDWARE\",\"available\":true}");
        Map<String, Object> body = new HashMap<>();
        body.put("listingId", listingId);
        body.put("text", text);
        ResponseEntity<ConversationView> started = rest.exchange("/conversations", HttpMethod.POST,
                new HttpEntity<>(body, headers(buyer)), ConversationView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Chat(started.getBody().id(), buyer, seller);
    }

    private MessageView send(String sub, String conversationId, String text) {
        ResponseEntity<MessageView> sent = rest.exchange("/conversations/" + conversationId + "/messages",
                HttpMethod.POST, new HttpEntity<>(Map.of("text", text), headers(sub)), MessageView.class);
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return sent.getBody();
    }

    private static HttpHeaders headers(String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.mint(sub));
        return headers;
    }

    private int unreadPushesFor(String sub) throws Exception {
        List<String> received = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            String raw = pushes.poll(100, TimeUnit.MILLISECONDS);
            if (raw != null) {
                received.add(raw);
            }
        }
        int count = 0;
        for (String raw : received) {
            JsonNode envelope = objectMapper.readTree(raw);
            if (RealtimePublisher.UNREAD.equals(envelope.path("destination").asText())
                    && sub.equals(envelope.path("userSub").asText())) {
                count++;
            }
        }
        return count;
    }

    private void startListenerAndWaitForSubscription() throws Exception {
        listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(redisConnectionFactory);
        listener.addMessageListener((message, pattern) -> pushes.offer(
                new String(message.getBody(), StandardCharsets.UTF_8)), ChannelTopic.of(channel));
        listener.afterPropertiesSet();
        listener.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            publisher.push("probe", RealtimePublisher.PRESENCE, Map.of("ready", true));
            if (pushes.poll(100, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(200);
                pushes.clear();
                return;
            }
        }
        throw new AssertionError("Redis subscriber did not receive the readiness probe");
    }

    private static String sub(String role) {
        return role + "-" + UUID.randomUUID();
    }

    private record Chat(String id, String buyer, String seller) {
    }
}
