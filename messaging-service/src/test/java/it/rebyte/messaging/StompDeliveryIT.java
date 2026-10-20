package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.messaging.dto.ConversationView;
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.StompTestClient;
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
class StompDeliveryIT extends MessagingTestBase {

    private static final StubHttpServer CATALOG = new StubHttpServer();
    private static final StubHttpServer USERS = new StubHttpServer();
    private static final List<String> QUEUES = List.of("/user/queue/messages", "/user/queue/notifications",
            "/user/queue/unread");

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

    @Value("${local.server.port}") int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;
    @Autowired SimpUserRegistry users;
    @Autowired RealtimePublisher publisher;

    @Test
    void restMessagesArriveAsUtf8JsonWithNotificationsAndUnreadOnlyForTheRecipient() throws Exception {
        String listingId = UUID.randomUUID().toString();
        CATALOG.respond("/internal/listings/" + listingId, 200,
                "{\"id\":\"" + listingId + "\",\"sellerSub\":\"alice\",\"listingTitle\":\"Monitor\","
                        + "\"price\":80.00,\"type\":\"HARDWARE\",\"available\":true}");
        for (String sub : List.of("alice", "bob")) {
            USERS.respond("/internal/users/" + sub, 200,
                    "{\"sub\":\"" + sub + "\",\"username\":\"" + sub + "\"}");
        }
        try (StompTestClient alice = connected("alice"); StompTestClient carol = connected("carol")) {
            subscribeAndWait(alice, "alice");
            subscribeAndWait(carol, "carol");
            waitForRedisRelay(alice);

            String firstText = "È ancora disponibile?";
            var started = rest.exchange("/conversations", HttpMethod.POST,
                    new HttpEntity<>(Map.of("listingId", listingId, "text", firstText), headers("bob")),
                    ConversationView.class);
            assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String conversationId = started.getBody().id();
            assertMessage(alice, conversationId, firstText, 1);
            String notificationId = assertNotification(alice, 1);
            assertUnread(alice, 1);

            String secondText = "Città, perché no? \uD83D\uDE00 Ñandú";
            var sent = rest.exchange("/conversations/" + conversationId + "/messages", HttpMethod.POST,
                    new HttpEntity<>(Map.of("text", secondText), headers("bob")), MessageView.class);
            assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertMessage(alice, conversationId, secondText, 2);
            assertThat(assertNotification(alice, 2)).isEqualTo(notificationId);
            assertUnread(alice, 2);

            for (String queue : QUEUES) {
                assertThat(carol.poll(queue, Duration.ofSeconds(2))).isNull();
            }
            assertThat(alice.failure()).isNotDone();
            assertThat(carol.failure()).isNotDone();
        }
    }

    private StompTestClient connected(String sub) throws Exception {
        StompTestClient client = new StompTestClient();
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + TestJwtSupport.mint(sub));
        try {
            client.connect("ws://localhost:" + port + "/ws", headers).get(5, TimeUnit.SECONDS);
            return client;
        } catch (Exception failure) {
            client.close();
            throw failure;
        }
    }

    private void subscribeAndWait(StompTestClient client, String sub) {
        QUEUES.forEach(client::subscribe);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(users.getUser(sub)).isNotNull();
            assertThat(users.getUser(sub).getSessions()).singleElement().satisfies(session ->
                    assertThat(session.getSubscriptions()).extracting(subscription -> subscription.getDestination())
                            .containsExactlyInAnyOrderElementsOf(QUEUES));
        });
    }

    private void waitForRedisRelay(StompTestClient alice) {
        // User registry events precede broker subscription handling; a round-trip also proves Redis is ready.
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            publisher.push("alice", RealtimePublisher.UNREAD, Map.of("probe", true));
            return alice.poll("/user/queue/unread", Duration.ofMillis(100)) != null;
        });
    }

    private JsonNode json(StompTestClient client, String destination) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            StompTestClient.Frame frame = client.poll(destination, Duration.ofNanos(deadline - System.nanoTime()));
            assertThat(frame).as("frame on %s", destination).isNotNull();
            assertThat(frame.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            JsonNode body = objectMapper.readTree(frame.body());
            if (!body.has("probe")) {
                return body;
            }
        }
        throw new AssertionError("No application frame on " + destination);
    }

    private void assertMessage(StompTestClient client, String id, String text, long seq) throws Exception {
        JsonNode body = json(client, "/user/queue/messages");
        assertThat(body.path("type").asText()).isEqualTo("message");
        assertThat(body.path("conversationId").asText()).isEqualTo(id);
        assertThat(body.path("message").path("seq").asLong()).isEqualTo(seq);
        assertThat(body.path("message").path("text").asText().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(text.getBytes(StandardCharsets.UTF_8));
    }

    private String assertNotification(StompTestClient client, long seq) throws Exception {
        JsonNode body = json(client, "/user/queue/notifications");
        assertThat(body.path("id").asText()).isNotBlank();
        assertThat(body.path("type").asText()).isEqualTo("MESSAGE");
        assertThat(body.path("target").path("seq").asLong()).isEqualTo(seq);
        return body.path("id").asText();
    }

    private void assertUnread(StompTestClient client, int count) throws Exception {
        JsonNode body = json(client, "/user/queue/unread");
        assertThat(body.path("messages").asInt()).isEqualTo(count);
        assertThat(body.path("notifications").asInt()).isEqualTo(1);
    }

    private static HttpHeaders headers(String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.mint(sub));
        return headers;
    }
}
