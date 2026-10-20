package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.realtime.RealtimePublisher;
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
class MessageSendIT extends MessagingTestBase {

    private static final StubHttpServer CATALOG = new StubHttpServer();
    private static final StubHttpServer USERS = new StubHttpServer();
    private static final String TITLE = "Monitor usato";

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
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired ObjectMapper objectMapper;
    @Autowired RealtimePublisher publisher;

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
    void participantsAlternateWithSequencedMessagesCountersAndOneUnreadNotificationPerRecipient() {
        Chat chat = startChat();

        ResponseEntity<MessageView> reply = send(chat.seller(), chat.id(), "Sì, è disponibile");
        ResponseEntity<MessageView> again = send(chat.buyer(), chat.id(), "Perfetto, grazie");

        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reply.getBody().seq()).isEqualTo(2);
        assertThat(again.getBody().seq()).isEqualTo(3);

        List<Message> messages = mongo.find(Query.query(Criteria.where("conversationId").is(chat.id()))
                .with(Sort.by("seq")), Message.class);
        assertThat(messages).extracting(Message::getSeq).containsExactly(1L, 2L, 3L);
        assertThat(messages).extracting(Message::getSenderSub).containsExactly(chat.buyer(), chat.seller(), chat.buyer());

        Conversation stored = mongo.findById(chat.id(), Conversation.class);
        assertThat(stored.unreadFor(chat.buyer())).isEqualTo(1);
        assertThat(stored.unreadFor(chat.seller())).isEqualTo(2);

        assertThat(mongo.find(Query.query(Criteria.where("message.conversationId").is(chat.id())), Notification.class))
                .hasSize(2);
        for (Message message : messages.subList(1, 3)) {
            List<Notification> notifications = mongo.find(Query.query(Criteria.where("type")
                    .is(NotificationType.MESSAGE).and("message.messageId").is(message.getId())), Notification.class);
            assertThat(notifications).singleElement()
                    .satisfies(n -> assertThat(n.getRecipientSub()).isEqualTo(message.getRecipientSub()));
        }
    }

    @Test
    void unicodeTextRoundTripsByteIdentical() {
        Chat chat = startChat();
        String text = "Città, perché no? 😀 Ñandú";

        ResponseEntity<MessageView> sent = send(chat.seller(), chat.id(), text);

        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sent.getBody().text().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sendingAfterTheListingIsGoneNeverCallsCatalog() {
        Chat chat = startChat();

        CATALOG.respond("/internal/listings/" + chat.listingId(), 404, "{\"title\":\"listing-not-found\"}");
        CATALOG.clearRequests();
        assertThat(send(chat.seller(), chat.id(), "Venduto altrove, scusa").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        listing(chat.listingId(), chat.seller(), false);
        assertThat(send(chat.buyer(), chat.id(), "Nessun problema").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(CATALOG.requests()).isEmpty();
    }

    @Test
    void outsiderCannotSendOrReadAndNothingIsStored() {
        Chat chat = startChat();
        String outsider = "outsider-" + UUID.randomUUID();

        ResponseEntity<String> post = rest.exchange("/conversations/" + chat.id() + "/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("text", "Ciao"), headers(outsider)), String.class);
        ResponseEntity<String> get = rest.exchange("/conversations/" + chat.id(), HttpMethod.GET,
                new HttpEntity<>(headers(outsider)), String.class);

        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post.getBody()).contains("\"title\":\"conversation-not-found\"");
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get.getBody()).contains("\"title\":\"conversation-not-found\"");
        assertThat(mongo.find(Query.query(Criteria.where("conversationId").is(chat.id())), Message.class)).hasSize(1);
        assertThat(mongo.findById(chat.id(), Conversation.class).getMessageSeq()).isEqualTo(1);
    }

    @Test
    void messagesCannotBeEditedOrDeleted() {
        Chat chat = startChat();
        Message original = mongo.findOne(Query.query(Criteria.where("conversationId").is(chat.id())), Message.class);
        String path = "/conversations/" + chat.id() + "/messages/" + original.getId();

        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            ResponseEntity<String> response = rest.exchange(path, method,
                    new HttpEntity<>(Map.of("text", "modificato"), headers(chat.buyer())), String.class);
            assertThat(response.getStatusCode().value()).as(method.name()).isIn(404, 405);
        }

        Message after = mongo.findById(original.getId(), Message.class);
        assertThat(after.getText()).isEqualTo(original.getText());
    }

    @Test
    void recipientReceivesAMessagesPushEnvelope() throws Exception {
        Chat chat = startChat();
        startListenerAndWaitForSubscription();

        MessageView sent = send(chat.seller(), chat.id(), "Ti mando le foto").getBody();

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String raw = pushes.poll(200, TimeUnit.MILLISECONDS);
            if (raw == null) {
                continue;
            }
            JsonNode envelope = objectMapper.readTree(raw);
            if (RealtimePublisher.MESSAGES.equals(envelope.path("destination").asText())
                    && chat.buyer().equals(envelope.path("userSub").asText())) {
                JsonNode payload = objectMapper.readTree(envelope.path("payload").asText());
                assertThat(payload.path("type").asText()).isEqualTo("message");
                assertThat(payload.path("conversationId").asText()).isEqualTo(chat.id());
                assertThat(payload.path("message").path("id").asText()).isEqualTo(sent.id());
                return;
            }
        }
        throw new AssertionError("No /queue/messages envelope for the recipient");
    }

    private Chat startChat() {
        String buyer = "buyer-" + UUID.randomUUID();
        String seller = "seller-" + UUID.randomUUID();
        String listingId = UUID.randomUUID().toString();
        listing(listingId, seller, true);
        Map<String, Object> body = new HashMap<>();
        body.put("listingId", listingId);
        body.put("text", "È ancora disponibile?");
        ResponseEntity<ConversationView> started = rest.exchange("/conversations", HttpMethod.POST,
                new HttpEntity<>(body, headers(buyer)), ConversationView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Chat(started.getBody().id(), listingId, buyer, seller);
    }

    private static void listing(String id, String sellerSub, boolean available) {
        CATALOG.respond("/internal/listings/" + id, 200, "{\"id\":\"" + id + "\",\"sellerSub\":\"" + sellerSub
                + "\",\"listingTitle\":\"" + TITLE + "\",\"price\":80.00,\"type\":\"HARDWARE\",\"available\":"
                + available + "}");
    }

    private ResponseEntity<MessageView> send(String sub, String conversationId, String text) {
        return rest.exchange("/conversations/" + conversationId + "/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("text", text), headers(sub)), MessageView.class);
    }

    private static HttpHeaders headers(String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.mint(sub));
        return headers;
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
                pushes.clear();
                return;
            }
        }
        throw new AssertionError("Redis subscriber did not receive the readiness probe");
    }

    private record Chat(String id, String listingId, String buyer, String seller) {
    }
}
