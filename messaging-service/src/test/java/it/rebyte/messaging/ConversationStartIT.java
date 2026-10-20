package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
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

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.dto.ConversationView;
import it.rebyte.messaging.outbox.OutboxEvent;
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
class ConversationStartIT extends MessagingTestBase {

    private static final StubHttpServer CATALOG = new StubHttpServer();
    private static final StubHttpServer USERS = new StubHttpServer();
    private static final String TITLE = "Scheda video Gigabyte · edizione città";
    private static final String TEXT = "Ciao, è ancora disponibile?";

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

    @Test
    void buyerStartsAConversationWithSnapshotsNotificationAndTextFreeEmailJob() {
        String buyer = sub("buyer");
        String seller = sub("seller");
        String listingId = listing(seller, "HARDWARE", true);
        USERS.respond("/internal/users/" + buyer, 200, "{\"sub\":\"" + buyer + "\",\"username\":\"Giulia\"}");
        USERS.respond("/internal/users/" + seller, 200, "{\"sub\":\"" + seller + "\",\"username\":\"Marco\"}");

        ResponseEntity<ConversationView> started = start(buyer, listingId, TEXT, ConversationView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ConversationView view = started.getBody();
        assertThat(view.listingTitle()).isEqualTo(TITLE);
        assertThat(view.listingPrice()).isEqualByComparingTo("129.90");
        assertThat(view.listingType()).isEqualTo("HARDWARE");
        assertThat(view.otherParticipantSub()).isEqualTo(seller);
        assertThat(view.otherParticipantUsername()).isEqualTo("Marco");
        assertThat(view.unreadFor()).isZero();

        ResponseEntity<ConversationView> sellerView = get(seller, view.id(), ConversationView.class);
        assertThat(sellerView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sellerView.getBody().unreadFor()).isEqualTo(1);
        assertThat(sellerView.getBody().lastMessage().preview()).isEqualTo(TEXT);
        assertThat(sellerView.getBody().otherParticipantUsername()).isEqualTo("Giulia");

        List<Message> messages = mongo.find(byField("conversationId", view.id()), Message.class);
        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.getSeq()).isEqualTo(1);
            assertThat(message.getSenderSub()).isEqualTo(buyer);
            assertThat(message.getRecipientSub()).isEqualTo(seller);
            assertThat(message.getText()).isEqualTo(TEXT);
        });

        List<Notification> notifications = mongo.find(byField("recipientSub", seller), Notification.class);
        assertThat(notifications).singleElement().satisfies(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.MESSAGE);
            assertThat(notification.getSnapshot()).containsEntry("actorUsername", "Giulia")
                    .containsEntry("listingTitle", TITLE);
            assertThat(notification.getMessage().conversationId()).isEqualTo(view.id());
            assertThat(notification.getMessage().seq()).isEqualTo(1);
        });

        List<OutboxEvent> jobs = emailJobsFor(seller);
        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.getPayload()).contains("\"NEW_MESSAGE\"").contains(view.id()).doesNotContain(TEXT);
        });
    }

    @Test
    void softwareListingCanBeStartedToo() {
        String buyer = sub("buyer");
        String listingId = listing(sub("seller"), "SOFTWARE", true);

        ResponseEntity<ConversationView> started = start(buyer, listingId, "Supporta Linux?", ConversationView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(started.getBody().listingType()).isEqualTo("SOFTWARE");
    }

    @Test
    void sellerCannotStartOnOwnListing() {
        String seller = sub("seller");
        String listingId = listing(seller, "HARDWARE", true);

        assertProblem(start(seller, listingId, TEXT, String.class), HttpStatus.UNPROCESSABLE_CONTENT,
                "self-conversation");
        assertNothingStoredFor(listingId, seller);
    }

    @Test
    void unavailableUnknownAndFailingListingsAreRefusedWithoutSideEffects() {
        String buyer = sub("buyer");
        String seller = sub("seller");

        String inactive = listing(seller, "HARDWARE", false);
        assertProblem(start(buyer, inactive, TEXT, String.class), HttpStatus.UNPROCESSABLE_CONTENT,
                "listing-not-available");
        assertNothingStoredFor(inactive, seller);

        String unknown = UUID.randomUUID().toString();
        CATALOG.respond("/internal/listings/" + unknown, 404, "{\"title\":\"listing-not-found\"}");
        assertProblem(start(buyer, unknown, TEXT, String.class), HttpStatus.NOT_FOUND, "listing-not-found");
        assertNothingStoredFor(unknown, seller);

        String failing = UUID.randomUUID().toString();
        CATALOG.respond("/internal/listings/" + failing, 500, "{}");
        assertProblem(start(buyer, failing, TEXT, String.class), HttpStatus.SERVICE_UNAVAILABLE,
                "catalog-unavailable");
        assertNothingStoredFor(failing, seller);
    }

    @Test
    void secondStartReturnsConflictCarryingTheExistingConversationId() {
        String buyer = sub("buyer");
        String seller = sub("seller");
        String listingId = listing(seller, "HARDWARE", true);
        String firstId = start(buyer, listingId, TEXT, ConversationView.class).getBody().id();

        ResponseEntity<Map<String, Object>> second = start(buyer, listingId, "Ancora io",
                new ParameterizedTypeReference<>() { });

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("title", "conversation-exists")
                .containsEntry("conversationId", firstId);
        assertThat(mongo.find(byField("conversationId", firstId), Message.class)).hasSize(1);
        assertThat(emailJobsFor(seller)).hasSize(1);
    }

    @Test
    void concurrentStartsCreateOneConversation() throws Exception {
        String buyer = sub("buyer");
        String seller = sub("seller");
        String listingId = listing(seller, "HARDWARE", true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ResponseEntity<Map<String, Object>>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String text = "Primo messaggio " + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return start(buyer, listingId, text, new ParameterizedTypeReference<Map<String, Object>>() { });
                }));
            }
            ready.await();
            go.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            List<Map<String, Object>> bodies = new ArrayList<>();
            for (Future<ResponseEntity<Map<String, Object>>> future : futures) {
                ResponseEntity<Map<String, Object>> response = future.get();
                statuses.add(HttpStatus.valueOf(response.getStatusCode().value()));
                bodies.add(response.getBody());
            }

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            List<Conversation> stored = mongo.find(Query.query(Criteria.where("listingId").is(listingId)
                    .and("buyerSub").is(buyer)), Conversation.class);
            assertThat(stored).hasSize(1);
            Map<String, Object> conflict = bodies.get(statuses.indexOf(HttpStatus.CONFLICT));
            assertThat(conflict).containsEntry("conversationId", stored.getFirst().getId());
            assertThat(mongo.find(byField("conversationId", stored.getFirst().getId()), Message.class)).hasSize(1);
            assertThat(mongo.find(byField("recipientSub", seller), Notification.class)).hasSize(1);
            assertThat(emailJobsFor(seller)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void emptyBlankAndMissingTextAreRejectedWithoutSideEffects() {
        String buyer = sub("buyer");
        String seller = sub("seller");
        String listingId = listing(seller, "HARDWARE", true);

        assertProblem(start(buyer, listingId, "", String.class), HttpStatus.BAD_REQUEST, "invalid-message-text");
        assertProblem(start(buyer, listingId, " \n\t ", String.class), HttpStatus.BAD_REQUEST,
                "invalid-message-text");
        assertProblem(start(buyer, listingId, null, String.class), HttpStatus.BAD_REQUEST, "invalid-message-text");
        assertNothingStoredFor(listingId, seller);
    }

    @Test
    void outsiderGetsNotFoundForAnExistingConversation() {
        String buyer = sub("buyer");
        String listingId = listing(sub("seller"), "HARDWARE", true);
        String id = start(buyer, listingId, TEXT, ConversationView.class).getBody().id();

        assertProblem(get(sub("outsider"), id, String.class), HttpStatus.NOT_FOUND, "conversation-not-found");
        assertProblem(get(buyer, UUID.randomUUID().toString(), String.class), HttpStatus.NOT_FOUND,
                "conversation-not-found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../users/alice", "listing/child", "%2e%2e%2fusers", "listing",
            "11111111-1111-1111-1111-11111111111z"})
    void invalidListingIdsAnswerValidationProblemsWithoutCallingCatalog(String listingId) {
        String buyer = sub("buyer");
        CATALOG.clearRequests();

        var response = start(buyer, listingId, TEXT, String.class);

        assertProblem(response, HttpStatus.BAD_REQUEST, "validation-failed");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains("listingId");
        assertThat(CATALOG.requests()).isEmpty();
        assertNothingStoredFor(listingId, sub("seller"));
    }

    @Test
    void anonymousStartIsRejected() {
        ResponseEntity<String> response = rest.exchange("/conversations", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", "x", "text", TEXT), headers(null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void usernameFailureBeforeStartStoresNothingAndRetryResolvesSender() {
        String buyer = sub("buyer");
        String seller = sub("seller");
        String listingId = listing(seller, "HARDWARE", true);
        USERS.respond("/internal/users/" + buyer, 503, "{}");
        assertProblem(start(buyer, listingId, TEXT, String.class), HttpStatus.SERVICE_UNAVAILABLE,
                "user-service-unavailable");
        assertNothingStoredFor(listingId, seller);
        USERS.respond("/internal/users/" + buyer, 200,
                "{\"sub\":\"" + buyer + "\",\"username\":\"Giulia\"}");
        USERS.respond("/internal/users/" + seller, 503, "{}");
        ResponseEntity<ConversationView> started = start(buyer, listingId, TEXT, ConversationView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get(buyer, started.getBody().id(), ConversationView.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mongo.find(Query.query(Criteria.where("recipientSub").is(seller)), Notification.class))
                .singleElement().satisfies(notification -> assertThat(notification.getSnapshot())
                        .containsEntry("actorUsername", "Giulia"));
        assertThat(mongo.find(Query.query(Criteria.where("routingKey").is(RabbitConfig.EMAIL_SEND_ROUTING_KEY)),
                OutboxEvent.class)).anySatisfy(job -> assertThat(job.getPayload())
                        .contains(started.getBody().id(), "Giulia"));
    }

    private String listing(String sellerSub, String type, boolean available) {
        String id = UUID.randomUUID().toString();
        CATALOG.respond("/internal/listings/" + id, 200, "{\"id\":\"" + id + "\",\"sellerSub\":\"" + sellerSub
                + "\",\"listingTitle\":\"" + TITLE + "\",\"price\":129.90,\"shippingCost\":5.00,\"type\":\"" + type
                + "\",\"paymentMethods\":[\"PAYPAL\"],\"available\":" + available + ",\"version\":3}");
        return id;
    }

    private <T> ResponseEntity<T> start(String sub, String listingId, String text, Class<T> type) {
        return rest.exchange("/conversations", HttpMethod.POST, new HttpEntity<>(body(listingId, text), headers(sub)),
                type);
    }

    private <T> ResponseEntity<T> start(String sub, String listingId, String text,
            ParameterizedTypeReference<T> type) {
        return rest.exchange("/conversations", HttpMethod.POST, new HttpEntity<>(body(listingId, text), headers(sub)),
                type);
    }

    private <T> ResponseEntity<T> get(String sub, String id, Class<T> type) {
        return rest.exchange("/conversations/" + id, HttpMethod.GET, new HttpEntity<>(headers(sub)), type);
    }

    private static Map<String, Object> body(String listingId, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("listingId", listingId);
        body.put("text", text);
        return body;
    }

    private static HttpHeaders headers(String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sub != null) {
            headers.setBearerAuth(TestJwtSupport.mint(sub));
        }
        return headers;
    }

    private static void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).contains("\"title\":\"" + code + "\"");
    }

    private void assertNothingStoredFor(String listingId, String sellerSub) {
        assertThat(mongo.find(byField("listingId", listingId), Conversation.class)).isEmpty();
        assertThat(mongo.find(byField("recipientSub", sellerSub), Message.class)).isEmpty();
        assertThat(mongo.find(byField("recipientSub", sellerSub), Notification.class)).isEmpty();
        assertThat(emailJobsFor(sellerSub)).isEmpty();
    }

    private List<OutboxEvent> emailJobsFor(String recipientSub) {
        return mongo.find(Query.query(Criteria.where("routingKey").is(RabbitConfig.EMAIL_SEND_ROUTING_KEY)
                .and("payload").regex(Pattern.quote(recipientSub))), OutboxEvent.class);
    }

    private static Query byField(String field, String value) {
        return Query.query(Criteria.where(field).is(value));
    }

    private static String sub(String role) {
        return role + "-" + UUID.randomUUID();
    }
}
