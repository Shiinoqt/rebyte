package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.messaging.OfferEvent;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.service.OfferCardService;
import it.rebyte.messaging.support.MessagingInfraTestBase;
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
class OfferCardIT extends MessagingInfraTestBase {

    private static final StubHttpServer CATALOG = new StubHttpServer();
    private static final StubHttpServer USERS = new StubHttpServer();
    private final List<JsonNode> envelopes = new CopyOnWriteArrayList<>();

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

    @Autowired MongoTemplate mongo;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate rest;
    @Autowired OfferCardService cards;
    @Autowired RealtimePublisher realtime;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Value("${rebyte.realtime.push-channel}") String channel;
    private RedisMessageListenerContainer listener;

    @BeforeEach
    void cleanAndSubscribe() throws Exception {
        for (Class<?> type : List.of(Notification.class, Message.class, Conversation.class, OutboxEvent.class)) {
            mongo.remove(new Query(), type);
        }
        for (String queue : List.of(RabbitConfig.OFFER_NOTIFY_QUEUE, RabbitConfig.OFFER_NOTIFY_DLQ,
                RabbitConfig.OFFER_CARD_QUEUE, RabbitConfig.OFFER_CARD_DLQ)) {
            rabbitAdmin.purgeQueue(queue, false);
        }
        listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(redisConnectionFactory);
        listener.addMessageListener((message, pattern) -> {
            try {
                envelopes.add(mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8)));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        }, ChannelTopic.of(channel));
        listener.afterPropertiesSet();
        listener.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            realtime.push("probe", RealtimePublisher.PRESENCE, Map.of("ready", true));
            return !envelopes.isEmpty();
        });
        envelopes.clear();
    }

    @AfterEach
    void stopListener() throws Exception {
        if (listener != null) {
            listener.stop();
            listener.destroy();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "ACCEPTED", "REJECTED", "WITHDRAWN", "EXPIRED"})
    void firstContactStoresEachTransitionWithCorrectSenderUnreadAndPushExactlyOnce(String transition) throws Exception {
        OfferEvent event = event(transition);
        stubUsers(event);
        publish(transition, event);
        publish(transition, event);
        drain();

        Conversation conversation = conversation(event);
        assertThat(mongo.findAll(Conversation.class)).hasSize(1);
        assertThat(conversation.getListingType()).isEqualTo("HARDWARE");
        assertThat(conversation.getListingTitle()).isEqualTo(event.listingTitle());
        assertThat(conversation.getListingPrice()).isEqualByComparingTo(event.askingPrice());
        assertThat(conversation.getBuyerSub()).isEqualTo(event.buyerSub());
        assertThat(conversation.getSellerSub()).isEqualTo(event.sellerSub());
        assertThat(conversation.getMessageSeq()).isEqualTo(1);
        boolean buyerSends = buyerSends(transition);
        assertThat(conversation.getBuyerUnread()).isEqualTo(buyerSends ? 0 : 1);
        assertThat(conversation.getSellerUnread()).isEqualTo(buyerSends ? 1 : 0);
        List<Message> messages = messages(conversation);
        assertThat(messages).hasSize(1);
        assertCard(messages.getFirst(), transition, event, 1);
        assertNoMessageNotifications();
        assertThat(mongo.findAll(OutboxEvent.class)).isEmpty();
        assertPush(messages.getFirst(), 1);
        assertThat(envelopes.stream().filter(envelope ->
                RealtimePublisher.MESSAGES.equals(envelope.path("destination").asText()))).hasSize(1);
    }

    @Test
    void createdAfterRestStartAppendsAtSequenceTwoWithoutAnotherMessageNotification() throws Exception {
        OfferEvent event = event("CREATED");
        stubListingAndUsers(event);
        assertThat(start(event).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Conversation existing = conversation(event);
        publish("CREATED", event);
        drain();

        Conversation changed = conversation(event);
        assertThat(changed.getId()).isEqualTo(existing.getId());
        assertThat(changed.getMessageSeq()).isEqualTo(2);
        assertThat(changed.getSellerUnread()).isEqualTo(2);
        List<Message> messages = messages(changed);
        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst().getKind()).isEqualTo(MessageKind.CHAT);
        assertCard(messages.getLast(), "CREATED", event, 2);
        assertThat(mongo.find(Query.query(Criteria.where("type").is(NotificationType.MESSAGE)), Notification.class))
                .hasSize(1);
        assertPush(messages.getLast(), 2);
    }

    @Test
    void everyTransitionAppendsInOrderAndRedeliveryDoesNotConsumeSequenceOrUnread() throws Exception {
        OfferEvent created = event("CREATED");
        stubUsers(created);
        List<String> transitions = List.of("CREATED", "ACCEPTED", "REJECTED", "WITHDRAWN", "EXPIRED");
        long buyerUnread = 0;
        long sellerUnread = 0;
        for (int index = 0; index < transitions.size(); index++) {
            String transition = transitions.get(index);
            OfferEvent event = transition(created, transition);
            publish(transition, event);
            publish(transition, event);
            drain();
            if (buyerSends(transition)) {
                sellerUnread++;
            } else {
                buyerUnread++;
            }
            Conversation conversation = conversation(event);
            assertThat(conversation.getMessageSeq()).isEqualTo(index + 1);
            assertThat(conversation.getBuyerUnread()).isEqualTo(buyerUnread);
            assertThat(conversation.getSellerUnread()).isEqualTo(sellerUnread);
            List<Message> messages = messages(conversation);
            assertThat(messages).hasSize(index + 1);
            assertCard(messages.getLast(), transition, event, index + 1);
            assertPush(messages.getLast(), buyerSends(transition) ? sellerUnread : buyerUnread);
        }
        assertNoMessageNotifications();
        assertThat(envelopes.stream().filter(envelope ->
                RealtimePublisher.MESSAGES.equals(envelope.path("destination").asText()))).hasSize(5);
    }

    @Test
    void acceptedBeforeCreatedCreatesOneConversationAndKeepsArrivalOrder() throws Exception {
        OfferEvent created = event("CREATED");
        OfferEvent accepted = transition(created, "ACCEPTED");
        stubUsers(created);
        publish("ACCEPTED", accepted);
        drain();
        publish("CREATED", created);
        drain();

        Conversation conversation = conversation(created);
        assertThat(mongo.findAll(Conversation.class)).hasSize(1);
        List<Message> messages = messages(conversation);
        assertThat(messages).hasSize(2);
        assertCard(messages.getFirst(), "ACCEPTED", accepted, 1);
        assertCard(messages.getLast(), "CREATED", created, 2);
        assertThat(conversation.getBuyerUnread()).isEqualTo(1);
        assertThat(conversation.getSellerUnread()).isEqualTo(1);
        assertNoMessageNotifications();
        assertPush(messages.getFirst(), 1);
        assertPush(messages.getLast(), 1);
    }

    @Test
    void restStartRacingCreatedLeavesOneConversationAndLaterStartReturnsConflict() throws Exception {
        OfferEvent event = event("CREATED");
        stubListingAndUsers(event);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var restResult = executor.submit(() -> {
                ready.countDown();
                assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                return start(event);
            });
            var cardResult = executor.submit(() -> {
                ready.countDown();
                assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                cards.apply("CREATED", event);
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            ResponseEntity<String> response = restResult.get(20, TimeUnit.SECONDS);
            cardResult.get(20, TimeUnit.SECONDS);
            assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
            Conversation conversation = conversation(event);
            assertThat(mongo.findAll(Conversation.class)).hasSize(1);
            List<Message> messages = messages(conversation);
            assertThat(messages).hasSize(response.getStatusCode() == HttpStatus.CREATED ? 2 : 1);
            assertThat(messages.stream().filter(message -> message.getKind() == MessageKind.OFFER))
                    .singleElement().satisfies(message -> assertCard(message, "CREATED", event, message.getSeq()));
            assertThat(conversation.getMessageSeq()).isEqualTo(messages.size());
            assertThat(conversation.getSellerUnread()).isEqualTo(messages.size());
            assertThat(conversation.getBuyerUnread()).isZero();
        } finally {
            go.countDown();
        }
        ResponseEntity<String> later = start(event);
        assertThat(later.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(later.getBody()).contains("conversation-exists", conversation(event).getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "offerId", "listingId", "buyerSub", "sellerSub"})
    void malformedIdentifiersEndInCardDlq(String field) throws Exception {
        Map<String, Object> payload = mapper.convertValue(event("CREATED"),
                new com.fasterxml.jackson.core.type.TypeReference<HashMap<String, Object>>() { });
        payload.put(field, " ");
        send("offer.created", payload);
        var rejected = rabbit.receive(RabbitConfig.OFFER_CARD_DLQ, 10_000);
        assertThat(rejected).isNotNull();
        assertThat(mapper.readTree(rejected.getBody())).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(payload)));
        assertThat(rabbit.receive(RabbitConfig.OFFER_NOTIFY_DLQ, 10_000)).isNotNull();
        assertThat(mongo.findAll(Message.class)).isEmpty();
        assertThat(mongo.findAll(Conversation.class)).isEmpty();
        assertThat(mongo.findAll(Notification.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null"})
    void malformedJsonEndsInCardDlq(String body) {
        rabbit.send(RebyteExchanges.MARKETPLACE_EVENTS, "offer.created",
                new org.springframework.amqp.core.Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        assertThat(rabbit.receive(RabbitConfig.OFFER_CARD_DLQ, 10_000)).isNotNull();
        assertThat(rabbit.receive(RabbitConfig.OFFER_NOTIFY_DLQ, 10_000)).isNotNull();
        assertThat(mongo.findAll(Message.class)).isEmpty();
        assertThat(mongo.findAll(Conversation.class)).isEmpty();
    }

    @Test
    void unknownRoutingKeyIsRejected() throws Exception {
        rabbit.send("", RabbitConfig.OFFER_CARD_QUEUE, json(event("CREATED")));
        assertThat(rabbit.receive(RabbitConfig.OFFER_CARD_DLQ, 10_000)).isNotNull();
        assertThat(mongo.findAll(Message.class)).isEmpty();
        assertThat(mongo.findAll(Conversation.class)).isEmpty();
    }

    private void assertCard(Message message, String transition, OfferEvent event, long seq) {
        assertThat(message.getSeq()).isEqualTo(seq);
        assertThat(message.getKind()).isEqualTo(MessageKind.OFFER);
        assertThat(message.getSourceEventId()).isEqualTo(event.eventId());
        assertThat(message.getSenderSub()).isEqualTo(buyerSends(transition) ? event.buyerSub() : event.sellerSub());
        assertThat(message.getRecipientSub()).isEqualTo(buyerSends(transition) ? event.sellerSub() : event.buyerSub());
        assertThat(message.getText()).isNull();
        assertThat(message.getImageKeys()).isNull();
        assertThat(message.getOffer().offerId()).isEqualTo(event.offerId());
        assertThat(message.getOffer().event()).isEqualTo(Message.OfferEvent.valueOf(transition));
        assertThat(message.getOffer().status()).isEqualTo(event.status());
        assertThat(message.getOffer().amount()).isEqualByComparingTo(event.amount());
        assertThat(message.getOffer().askingPrice()).isEqualByComparingTo(event.askingPrice());
        assertThat(message.getOffer().expiresAt()).isEqualTo(event.expiresAt());
        assertThat(message.getOffer().rejectionReason()).isEqualTo(event.rejectionReason());
    }

    private void assertPush(Message message, long unread) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(envelopes.stream().filter(envelope ->
                    message.getRecipientSub().equals(envelope.path("userSub").asText())
                    && RealtimePublisher.MESSAGES.equals(envelope.path("destination").asText()))
                    .map(this::payload).filter(payload -> message.getId().equals(payload.path("message").path("id").asText())))
                    .singleElement().satisfies(payload -> {
                        assertThat(payload.path("type").asText()).isEqualTo("message");
                        assertThat(payload.path("conversationId").asText()).isEqualTo(message.getConversationId());
                        assertThat(payload.path("message").path("seq").asLong()).isEqualTo(message.getSeq());
                        assertThat(payload.path("message").path("kind").asText()).isEqualTo("OFFER");
                        assertThat(payload.path("message").path("offer").path("event").asText())
                                .isEqualTo(message.getOffer().event().name());
                    });
            assertThat(envelopes.stream().filter(envelope ->
                    message.getRecipientSub().equals(envelope.path("userSub").asText())
                    && RealtimePublisher.UNREAD.equals(envelope.path("destination").asText()))
                    .map(this::payload).anyMatch(payload -> payload.path("messages").asLong() == unread)).isTrue();
        });
    }

    private JsonNode payload(JsonNode envelope) {
        try {
            return mapper.readTree(envelope.path("payload").asText());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void assertNoMessageNotifications() {
        assertThat(mongo.find(Query.query(Criteria.where("type").is(NotificationType.MESSAGE)), Notification.class))
                .isEmpty();
    }

    private Conversation conversation(OfferEvent event) {
        Conversation result = mongo.findOne(Query.query(Criteria.where("listingId").is(event.listingId())
                .and("buyerSub").is(event.buyerSub())), Conversation.class);
        assertThat(result).isNotNull();
        return result;
    }

    private List<Message> messages(Conversation conversation) {
        return mongo.find(Query.query(Criteria.where("conversationId").is(conversation.getId()))
                .with(Sort.by("seq")), Message.class);
    }

    private ResponseEntity<String> start(OfferEvent event) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.mint(event.buyerSub()));
        return rest.exchange("/conversations", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", event.listingId(), "text", "Disponibile?"), headers), String.class);
    }

    private void stubListingAndUsers(OfferEvent event) throws Exception {
        stubUsers(event);
        CATALOG.respond("/internal/listings/" + event.listingId(), 200, mapper.writeValueAsString(Map.of(
                "id", event.listingId(), "sellerSub", event.sellerSub(), "listingTitle", event.listingTitle(),
                "price", event.askingPrice(), "type", "HARDWARE", "available", true, "version", 1,
                "shippingCost", 5, "paymentMethods", List.of("PAYPAL"))));
    }

    private void stubUsers(OfferEvent event) throws Exception {
        USERS.respond("/internal/users/" + event.buyerSub(), 200,
                mapper.writeValueAsString(Map.of("sub", event.buyerSub(), "username", "Giulia")));
        USERS.respond("/internal/users/" + event.sellerSub(), 200,
                mapper.writeValueAsString(Map.of("sub", event.sellerSub(), "username", "Marco")));
    }

    private void publish(String transition, OfferEvent event) throws Exception {
        send("offer." + transition.toLowerCase(java.util.Locale.ROOT), event);
    }

    private void send(String routingKey, Object event) throws Exception {
        rabbit.send(RebyteExchanges.MARKETPLACE_EVENTS, routingKey, json(event));
    }

    private org.springframework.amqp.core.Message json(Object payload) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new org.springframework.amqp.core.Message(mapper.writeValueAsBytes(payload), properties);
    }

    private void drain() throws Exception {
        // Each FIFO consumer rejects this marker only after its earlier deliveries and pushes finish.
        for (String queue : List.of(RabbitConfig.OFFER_CARD_QUEUE, RabbitConfig.OFFER_NOTIFY_QUEUE)) {
            Map<String, String> marker = Map.of("marker", UUID.randomUUID().toString());
            rabbit.send("", queue, json(marker));
            var rejected = rabbit.receive(queue + ".dlq", 10_000);
            assertThat(rejected).isNotNull();
            assertThat(mapper.readTree(rejected.getBody())).isEqualTo(mapper.valueToTree(marker));
        }
        assertThat(rabbitAdmin.getQueueInfo(RabbitConfig.OFFER_NOTIFY_DLQ).getMessageCount()).isZero();
        assertThat(rabbitAdmin.getQueueInfo(RabbitConfig.OFFER_CARD_DLQ).getMessageCount()).isZero();
    }

    private static boolean buyerSends(String transition) {
        return List.of("CREATED", "WITHDRAWN", "EXPIRED").contains(transition);
    }

    private static OfferEvent event(String transition) {
        OfferEvent base = new OfferEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "Scheda video città", "buyer-" + UUID.randomUUID(),
                "seller-" + UUID.randomUUID(), new BigDecimal("99.50"), new BigDecimal("129.90"),
                "PENDING", null, null, Instant.parse("2026-10-05T12:00:00Z"));
        return transition(base, transition);
    }

    private static OfferEvent transition(OfferEvent base, String transition) {
        return new OfferEvent(UUID.randomUUID().toString(), base.offerId(), base.listingId(), base.listingTitle(),
                base.buyerSub(), base.sellerSub(), base.amount(), base.askingPrice(),
                "CREATED".equals(transition) ? "PENDING" : transition,
                "REJECTED".equals(transition) ? "SELLER_REJECTED" : null,
                "ACCEPTED".equals(transition) ? Instant.parse("2026-10-06T12:00:00Z") : null,
                base.occurredAt());
    }
}
