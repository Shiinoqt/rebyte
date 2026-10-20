package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.messaging.dto.ConversationPage;
import it.rebyte.messaging.dto.ConversationView;
import it.rebyte.messaging.realtime.PresenceService;
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
        "rebyte.realtime.presence.ttl-ms=3000",
        "rebyte.realtime.presence.refresh-ms=1000",
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *"
})
class PresenceTypingIT extends MessagingTestBase {

    private static final StubHttpServer CATALOG = new StubHttpServer();
    private static final StubHttpServer USERS = new StubHttpServer();
    private static final String PRESENCE = "/user/queue/presence";
    private static final String TYPING = "/user/queue/typing";
    private static final List<String> COLLECTIONS = List.of("conversation", "message", "notification",
            "outbox_event");

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
    @Autowired PresenceService presence;
    @Autowired StringRedisTemplate redis;
    @Autowired MongoTemplate mongo;
    @Autowired RealtimePublisher publisher;

    @Test
    void presenceIsPushedOnlyToConversationPartnersAndShownInViews() throws Exception {
        String alice = sub("alice");
        String bob = sub("bob");
        String carol = sub("carol");
        String conversationId = start(alice, bob);
        start(carol, sub("dave"));

        try (StompTestClient aliceClient = connected(alice); StompTestClient carolClient = connected(carol)) {
            subscribeAndWait(aliceClient, alice);
            subscribeAndWait(carolClient, carol);
            waitForRedisRelay(aliceClient, alice);

            StompTestClient bobClient = connected(bob);
            JsonNode online = json(aliceClient, PRESENCE);
            assertThat(online.path("userSub").asText()).isEqualTo(bob);
            assertThat(online.path("online").asBoolean()).isTrue();
            assertThat(carolClient.poll(PRESENCE, Duration.ofSeconds(1))).isNull();
            assertThat(partnerOnline(alice, conversationId)).isTrue();
            assertThat(listed(alice).partnerOnline()).isTrue();

            bobClient.close();
            JsonNode offline = json(aliceClient, PRESENCE);
            assertThat(offline.path("userSub").asText()).isEqualTo(bob);
            assertThat(offline.path("online").asBoolean()).isFalse();
            assertThat(partnerOnline(alice, conversationId)).isFalse();
            assertThat(carolClient.poll(PRESENCE, Duration.ofSeconds(1))).isNull();
        }
    }

    @Test
    void expiredPresenceEntriesDoNotCountAsOnline() {
        String ghost = sub("ghost");
        redis.opsForZSet().add("presence:" + ghost, "crashed-session", System.currentTimeMillis() - 1000);

        assertThat(presence.isOnline(ghost)).isFalse();
        assertThat(presence.onlineMap(List.of(ghost))).containsEntry(ghost, false);
    }

    @Test
    void typingIsRelayedToThePartnerOnlyThrottledAndNeverStored() throws Exception {
        String alice = sub("alice");
        String bob = sub("bob");
        String carol = sub("carol");
        String conversationId = start(alice, bob);

        try (StompTestClient aliceClient = connected(alice); StompTestClient bobClient = connected(bob);
                StompTestClient carolClient = connected(carol)) {
            subscribeAndWait(aliceClient, alice);
            subscribeAndWait(bobClient, bob);
            subscribeAndWait(carolClient, carol);
            waitForRedisRelay(bobClient, bob);
            Map<String, Long> countsBefore = counts();
            String destination = "/app/conversations/" + conversationId + "/typing";

            aliceClient.send(destination, new byte[0]);
            JsonNode typing = json(bobClient, TYPING);
            assertThat(typing.path("conversationId").asText()).isEqualTo(conversationId);
            assertThat(typing.path("userSub").asText()).isEqualTo(alice);
            assertThat(typing.path("ttlMs").asLong()).isEqualTo(5000);

            aliceClient.send(destination, new byte[0]);
            assertThat(bobClient.poll(TYPING, Duration.ofMillis(1000))).isNull();

            TimeUnit.MILLISECONDS.sleep(800);
            aliceClient.send(destination, new byte[0]);
            assertThat(json(bobClient, TYPING).path("userSub").asText()).isEqualTo(alice);

            carolClient.send(destination, new byte[0]);
            assertThat(bobClient.poll(TYPING, Duration.ofSeconds(2))).isNull();
            assertThat(aliceClient.poll(TYPING, Duration.ofMillis(200))).isNull();
            assertThat(counts()).isEqualTo(countsBefore);
        }
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new HashMap<>();
        COLLECTIONS.forEach(collection -> counts.put(collection,
                mongo.getCollection(collection).countDocuments()));
        return counts;
    }

    private boolean partnerOnline(String sub, String conversationId) {
        ResponseEntity<ConversationView> view = rest.exchange("/conversations/" + conversationId, HttpMethod.GET,
                new HttpEntity<>(headers(sub)), ConversationView.class);
        return view.getBody().partnerOnline();
    }

    private ConversationView listed(String sub) {
        ResponseEntity<ConversationPage> page = rest.exchange("/conversations", HttpMethod.GET,
                new HttpEntity<>(headers(sub)), ConversationPage.class);
        return page.getBody().items().getFirst();
    }

    private String start(String buyer, String seller) {
        String listingId = UUID.randomUUID().toString();
        CATALOG.respond("/internal/listings/" + listingId, 200, "{\"id\":\"" + listingId + "\",\"sellerSub\":\""
                + seller + "\",\"listingTitle\":\"Oggetto\",\"price\":10.00,\"type\":\"HARDWARE\",\"available\":true}");
        ResponseEntity<ConversationView> started = rest.exchange("/conversations", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", listingId, "text", "Ciao"), headers(buyer)),
                ConversationView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return started.getBody().id();
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
        List.of(PRESENCE, TYPING).forEach(client::subscribe);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(users.getUser(sub)).isNotNull();
            assertThat(users.getUser(sub).getSessions()).singleElement().satisfies(session ->
                    assertThat(session.getSubscriptions()).hasSize(2));
        });
    }

    private void waitForRedisRelay(StompTestClient client, String sub) {
        // The Redis relay subscribes asynchronously at startup; a probe round trip proves it is listening.
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            publisher.push(sub, RealtimePublisher.PRESENCE, Map.of("probe", true));
            return client.poll(PRESENCE, Duration.ofMillis(100)) != null;
        });
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1))
                .until(() -> client.poll(PRESENCE, Duration.ofMillis(50)) == null);
    }

    private JsonNode json(StompTestClient client, String destination) throws Exception {
        StompTestClient.Frame frame = client.poll(destination, Duration.ofSeconds(5));
        assertThat(frame).as("frame on %s", destination).isNotNull();
        return objectMapper.readTree(new String(frame.body(), StandardCharsets.UTF_8));
    }

    private static HttpHeaders headers(String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.mint(sub));
        return headers;
    }

    private static String sub(String role) {
        return role + "-" + UUID.randomUUID();
    }
}
