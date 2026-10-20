package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.dto.ConversationView;
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.service.NotificationWriter;
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
class MessageNotificationIT extends MessagingTestBase {

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
    @MockitoSpyBean NotificationWriter notificationWriter;

    @AfterEach
    void resetSpy() {
        reset(notificationWriter);
    }

    @Test
    void firstMessageToARecipientOpensAnUnreadNotification() {
        Chat chat = startChat();

        MessageView reply = send(chat.seller(), chat.id(), "Certo").getBody();

        List<Notification> notifications = mongo.find(Query.query(Criteria.where("recipientSub").is(chat.buyer())),
                Notification.class);
        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.MESSAGE);
            assertThat(notification.getDedupKey()).isEqualTo("MESSAGE:" + reply.id());
            assertThat(notification.isRead()).isFalse();
            assertThat(notification.getExpiresAt()).isNull();
        });
    }

    @Test
    void notificationFailureRollsBackTheWholeSendAndTheNextSendContinuesTheSequence() {
        Chat chat = startChat();
        Conversation before = mongo.findById(chat.id(), Conversation.class);
        long messagesBefore = count(Message.class, "conversationId", chat.id());
        long notificationsBefore = count(Notification.class, "recipientSub", chat.buyer());
        long outboxBefore = mongo.count(new Query(), OutboxEvent.class);

        doThrow(new RuntimeException("notification store down")).when(notificationWriter).coalesceMessage(any(), any());
        ResponseEntity<String> failed = rest.exchange("/conversations/" + chat.id() + "/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("text", "Questo non deve restare"), headers(chat.seller())), String.class);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Conversation after = mongo.findById(chat.id(), Conversation.class);
        assertThat(after.getMessageSeq()).isEqualTo(before.getMessageSeq());
        assertThat(after.unreadFor(chat.buyer())).isEqualTo(before.unreadFor(chat.buyer()));
        assertThat(count(Message.class, "conversationId", chat.id())).isEqualTo(messagesBefore);
        assertThat(count(Notification.class, "recipientSub", chat.buyer())).isEqualTo(notificationsBefore);
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isEqualTo(outboxBefore);

        reset(notificationWriter);
        ResponseEntity<MessageView> next = send(chat.seller(), chat.id(), "Riprovo");

        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(next.getBody().seq()).isEqualTo(before.getMessageSeq() + 1);
    }

    @Test
    void outboxCarriesOnlyEmailJobsAndNoMessageSentHop() {
        Chat chat = startChat();
        send(chat.seller(), chat.id(), "Prima risposta");
        send(chat.buyer(), chat.id(), "Seconda domanda");

        List<String> routingKeys = mongo.findDistinct(new Query(), "routingKey", OutboxEvent.class, String.class);

        assertThat(routingKeys).containsOnly(RabbitConfig.EMAIL_SEND_ROUTING_KEY);
    }

    private long count(Class<?> type, String field, String value) {
        return mongo.count(Query.query(Criteria.where(field).is(value)), type);
    }

    private Chat startChat() {
        String buyer = "buyer-" + UUID.randomUUID();
        String seller = "seller-" + UUID.randomUUID();
        String listingId = UUID.randomUUID().toString();
        CATALOG.respond("/internal/listings/" + listingId, 200, "{\"id\":\"" + listingId + "\",\"sellerSub\":\""
                + seller + "\",\"listingTitle\":\"Tastiera\",\"price\":40.00,\"type\":\"HARDWARE\",\"available\":true}");
        Map<String, Object> body = new HashMap<>();
        body.put("listingId", listingId);
        body.put("text", "Disponibile?");
        ResponseEntity<ConversationView> started = rest.exchange("/conversations", HttpMethod.POST,
                new HttpEntity<>(body, headers(buyer)), ConversationView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Chat(started.getBody().id(), buyer, seller);
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

    private record Chat(String id, String buyer, String seller) {
    }
}
