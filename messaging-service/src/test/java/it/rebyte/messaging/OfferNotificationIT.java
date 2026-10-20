package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
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

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.messaging.OfferEvent;
import it.rebyte.messaging.support.MessagingInfraTestBase;
import it.rebyte.messaging.support.StubHttpServer;

@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = "rebyte.outbox.relay.fixed-delay-ms=3600000")
class OfferNotificationIT extends MessagingInfraTestBase {

    private static final StubHttpServer USERS = new StubHttpServer();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.user-service.url", USERS::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        USERS.close();
    }

    @Autowired MongoTemplate mongo;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), Notification.class);
        mongo.remove(new Query(), it.rebyte.messaging.domain.Message.class);
        mongo.remove(new Query(), Conversation.class);
        for (String queue : List.of(RabbitConfig.OFFER_NOTIFY_QUEUE, RabbitConfig.OFFER_NOTIFY_DLQ,
                RabbitConfig.OFFER_CARD_QUEUE, RabbitConfig.OFFER_CARD_DLQ)) {
            rabbitAdmin.purgeQueue(queue, false);
        }
        USERS.clearRequests();
    }

    @Test
    void createdNotifiesOnlySellerWithBuyerUsernameAndPlainPricesOnce() throws Exception {
        OfferEvent event = event("PENDING");
        stubUser(event.buyerSub(), "Giulia");
        publish("offer.created", event);
        publish("offer.created", event);
        drain();

        assertThat(mongo.findAll(Notification.class)).singleElement().satisfies(notification -> {
            assertThat(notification.getRecipientSub()).isEqualTo(event.sellerSub());
            assertThat(notification.getType()).isEqualTo(NotificationType.OFFER_RECEIVED);
            assertThat(notification.getDedupKey()).isEqualTo("OFFER_RECEIVED:" + event.offerId());
            assertThat(notification.getSnapshot()).isEqualTo(Map.of("actorUsername", "Giulia",
                    "listingTitle", event.listingTitle(), "amount", "100", "askingPrice", "129.90"));
        });
        assertThat(USERS.requests()).hasSize(2).allSatisfy(request ->
                assertThat(request.path()).isEqualTo("/internal/users/" + event.buyerSub()));
    }

    @Test
    void acceptedNotifiesOnlyBuyerWithSellerUsernameAndHoldExpiryOnce() throws Exception {
        OfferEvent event = event("ACCEPTED");
        stubUser(event.sellerSub(), "Marco");
        publish("offer.accepted", event);
        publish("offer.accepted", event);
        drain();

        assertThat(mongo.findAll(Notification.class)).singleElement().satisfies(notification -> {
            assertThat(notification.getRecipientSub()).isEqualTo(event.buyerSub());
            assertThat(notification.getType()).isEqualTo(NotificationType.OFFER_ACCEPTED);
            assertThat(notification.getDedupKey()).isEqualTo("OFFER_ACCEPTED:" + event.offerId());
            assertThat(notification.getSnapshot()).isEqualTo(Map.of("actorUsername", "Marco",
                    "listingTitle", event.listingTitle(), "amount", "100", "expiresAt", event.expiresAt().toString()));
        });
        assertThat(USERS.requests()).hasSize(2).allSatisfy(request ->
                assertThat(request.path()).isEqualTo("/internal/users/" + event.sellerSub()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rejected", "withdrawn", "expired"})
    void excludedTransitionsCreateOnlyCardsAndNoNotification(String transition) throws Exception {
        OfferEvent event = event(transition.toUpperCase(java.util.Locale.ROOT));
        publish("offer." + transition, event);
        drain();

        assertThat(mongo.findAll(Notification.class)).isEmpty();
        assertThat(mongo.findAll(it.rebyte.messaging.domain.Message.class)).singleElement().satisfies(message ->
                assertThat(message.getSourceEventId()).isEqualTo(event.eventId()));
        assertThat(USERS.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "offerId", "listingId", "buyerSub", "sellerSub"})
    void missingIdentifiersEndInNotificationDlq(String field) throws Exception {
        Map<String, Object> payload = mapper.convertValue(event("PENDING"),
                new com.fasterxml.jackson.core.type.TypeReference<HashMap<String, Object>>() { });
        payload.remove(field);
        publish("offer.created", payload);

        Message rejected = rabbit.receive(RabbitConfig.OFFER_NOTIFY_DLQ, 10_000);
        assertThat(rejected).isNotNull();
        assertThat(mapper.readTree(rejected.getBody())).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(payload)));
        assertThat(rabbit.receive(RabbitConfig.OFFER_CARD_DLQ, 10_000)).isNotNull();
        assertThat(mongo.findAll(Notification.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null"})
    void malformedJsonEndsInNotificationDlq(String body) {
        rabbit.send(RebyteExchanges.MARKETPLACE_EVENTS, "offer.created",
                new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties()));
        assertThat(rabbit.receive(RabbitConfig.OFFER_NOTIFY_DLQ, 10_000)).isNotNull();
        assertThat(rabbit.receive(RabbitConfig.OFFER_CARD_DLQ, 10_000)).isNotNull();
        assertThat(mongo.findAll(Notification.class)).isEmpty();
    }

    @Test
    void unknownRoutingKeyIsRejected() throws Exception {
        rabbit.send("", RabbitConfig.OFFER_NOTIFY_QUEUE, json(event("PENDING")));
        assertThat(rabbit.receive(RabbitConfig.OFFER_NOTIFY_DLQ, 10_000)).isNotNull();
        assertThat(mongo.findAll(Notification.class)).isEmpty();
    }

    private void stubUser(String sub, String username) throws Exception {
        USERS.respond("/internal/users/" + sub, 200,
                mapper.writeValueAsString(Map.of("sub", sub, "username", username)));
    }

    private void publish(String routingKey, Object payload) throws Exception {
        rabbit.send(RebyteExchanges.MARKETPLACE_EVENTS, routingKey, json(payload));
    }

    private Message json(Object payload) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(mapper.writeValueAsBytes(payload), properties);
    }

    private void drain() throws Exception {
        // Rejection of a FIFO marker proves previous deliveries completed, including post-commit pushes.
        for (String queue : List.of(RabbitConfig.OFFER_NOTIFY_QUEUE, RabbitConfig.OFFER_CARD_QUEUE)) {
            Map<String, String> marker = Map.of("marker", UUID.randomUUID().toString());
            rabbit.send("", queue, json(marker));
            var rejected = rabbit.receive(queue + ".dlq", 10_000);
            assertThat(rejected).isNotNull();
            assertThat(mapper.readTree(rejected.getBody())).isEqualTo(mapper.valueToTree(marker));
        }
        assertThat(rabbitAdmin.getQueueInfo(RabbitConfig.OFFER_NOTIFY_DLQ).getMessageCount()).isZero();
        assertThat(rabbitAdmin.getQueueInfo(RabbitConfig.OFFER_CARD_DLQ).getMessageCount()).isZero();
    }

    private static OfferEvent event(String status) {
        return new OfferEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "Scheda video città", "buyer-" + UUID.randomUUID(),
                "seller-" + UUID.randomUUID(), new BigDecimal("1E+2"), new BigDecimal("129.90"),
                status, "REJECTED".equals(status) ? "SELLER_REJECTED" : null,
                "ACCEPTED".equals(status) ? Instant.parse("2026-10-06T12:00:00Z") : null,
                Instant.parse("2026-10-05T12:00:00Z"));
    }
}
