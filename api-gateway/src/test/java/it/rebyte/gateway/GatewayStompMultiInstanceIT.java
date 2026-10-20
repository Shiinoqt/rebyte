package it.rebyte.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.stomp.StompHeaders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.gateway.support.GatewayMessagingCluster;
import it.rebyte.messaging.support.StompTestClient;
import it.rebyte.messaging.support.TestJwtSupport;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class GatewayStompMultiInstanceIT {

    private static final List<String> QUEUES = List.of("/user/queue/messages", "/user/queue/notifications",
            "/user/queue/unread");
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(10);
    private final ObjectMapper json = new ObjectMapper();
    private final TestRestTemplate rest = new TestRestTemplate(new RestTemplateBuilder()
            .connectTimeout(Duration.ofSeconds(5)).readTimeout(FRAME_TIMEOUT));
    private final List<String> tokens = new ArrayList<>();
    private GatewayMessagingCluster cluster;
    private long scenariosStarted;

    @BeforeAll
    void startCluster() {
        long started = System.nanoTime();
        cluster = GatewayMessagingCluster.start();
        cluster.assertProductionConfiguration();
        scenariosStarted = System.nanoTime();
        System.out.printf("GatewayStompMultiInstanceIT cluster startup: %.3f seconds%n",
                (scenariosStarted - started) / 1_000_000_000.0);
    }

    @AfterEach
    void bearerTokensNeverEnterLogs(CapturedOutput output) {
        for (String token : tokens) {
            assertThat(output.getAll()).doesNotContain(token, "Bearer " + token);
        }
    }

    @AfterAll
    void stopCluster(CapturedOutput output) {
        try {
            if (cluster != null) {
                cluster.close();
                System.out.printf("GatewayStompMultiInstanceIT scenarios and shutdown: %.3f seconds%n",
                        (System.nanoTime() - scenariosStarted) / 1_000_000_000.0);
            }
        } finally {
            bearerTokensNeverEnterLogs(output);
        }
    }

    @Test
    @Order(1)
    void missingInvalidAndQueryStringConnectCredentialsAreRefused() throws Exception {
        refusedConnect(cluster.wsUrl(), new StompHeaders());
        StompHeaders empty = new StompHeaders();
        empty.set("Authorization", "Bearer ");
        refusedConnect(cluster.wsUrl(), empty);

        String malformed = tracked("invalid-" + UUID.randomUUID() + "~not-a-number");
        refusedConnect(cluster.wsUrl(), connectHeaders(malformed));
        String expired = tracked(TestJwtSupport.mintExpiring("invalid-" + UUID.randomUUID(),
                Instant.now().minusSeconds(60)));
        refusedConnect(cluster.wsUrl(), connectHeaders(expired));
        String queryToken = freshToken("query-" + UUID.randomUUID());
        refusedConnect(cluster.wsUrl() + "?access_token=" + queryToken, new StompHeaders());
        assertThat(cluster.upgrades()).allSatisfy(upgrade -> assertThat(upgrade.authorization()).isNull());
    }

    @ParameterizedTest
    @Order(2)
    @ValueSource(strings = {"/topic/anything", "/queue/messages", "/user/queue/other",
            "/user/bob/queue/messages"})
    void forbiddenSubscriptionsAreRefusedThroughTheGateway(String destination) throws Exception {
        try (StompTestClient client = connected(freshToken("forbidden-" + UUID.randomUUID()))) {
            client.subscribe(destination);
            assertErrorAndClosed(client);
        }
    }

    @Test
    @Order(3)
    void passiveTokenExpiryClosesTheGatewaySocketAndRemovesTheActualSession() throws Exception {
        String sub = "passive-" + UUID.randomUUID();
        Instant expiresAt = Instant.ofEpochSecond(Instant.now().plusSeconds(5).getEpochSecond());
        String token = tracked(TestJwtSupport.mintExpiring(sub, expiresAt));
        try (StompTestClient client = connected(token)) {
            client.subscribe(QUEUES.getFirst());
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(cluster.subscribedInstances(sub, List.of(QUEUES.getFirst()))).hasSize(1));
            assertThat(client.closed().get(12, TimeUnit.SECONDS)).isNotNull();
            assertThat(Instant.now()).isAfterOrEqualTo(expiresAt);
            assertThat(client.isConnected()).isFalse();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(cluster.sessionCount(sub)).isZero());
        }
    }

    @Test
    @Order(4)
    void realRestPushesFanOutToBothInstancesAndReconnectRefreshSurvivesInstanceLoss() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String alice = "seller-" + suffix;
        String bob = "buyer-" + suffix;
        String carol = "outsider-" + suffix;
        String listingId = UUID.randomUUID().toString();
        cluster.listing(listingId, alice);
        cluster.user(alice);
        cluster.user(bob);
        Map<String, StompTestClient> byInstance = new HashMap<>();
        List<StompTestClient> clients = new ArrayList<>();
        try {
            for (int attempt = 0; attempt < 8 && byInstance.size() < 2; attempt++) {
                StompTestClient client = connected(freshToken(alice));
                clients.add(client);
                QUEUES.forEach(client::subscribe);
                int expectedSessions = clients.size();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(cluster.sessionCount(alice)).isEqualTo(expectedSessions));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(cluster.subscribedInstances(alice, QUEUES)).isNotEmpty();
                    assertThat(cluster.subscribedSessionCount(alice, QUEUES)).isEqualTo(expectedSessions);
                });
                String instance = cluster.upgrades().getLast().instanceId();
                assertThat(cluster.subscribedInstances(alice, QUEUES)).contains(instance);
                byInstance.putIfAbsent(instance, client);
            }
            assertThat(byInstance).as("gateway must place authenticated subscriptions on both real instances")
                    .hasSize(2);
            assertThat(cluster.subscribedInstances(alice, QUEUES)).containsExactlyInAnyOrderElementsOf(
                    cluster.instanceIds());
            StompTestClient outsider = connected(freshToken(carol));
            clients.add(outsider);
            QUEUES.forEach(outsider::subscribe);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(cluster.subscribedSessionCount(carol, QUEUES)).isEqualTo(1));
            waitForRelays(alice, byInstance.values().stream().toList());
            waitForRelays(carol, List.of(outsider));

            String firstText = "È ancora disponibile? Città, perché no? Ñandú 漢字";
            JsonNode started = request(HttpMethod.POST, "/conversations", bob,
                    Map.of("listingId", listingId, "text", firstText), HttpStatus.CREATED).getBody();
            assertThat(started).isNotNull();
            String conversationId = started.path("id").asText();
            assertThat(conversationId).isNotBlank();
            for (StompTestClient client : byInstance.values()) {
                assertPushes(client, conversationId, firstText, 1, 1, 1);
            }

            String secondText = "Unicode conservato: àéîöü, 日本語";
            JsonNode sent = request(HttpMethod.POST, "/conversations/" + conversationId + "/messages", bob,
                    Map.of("text", secondText), HttpStatus.CREATED).getBody();
            assertThat(sent).isNotNull();
            for (StompTestClient client : byInstance.values()) {
                assertPushes(client, conversationId, secondText, 2, 2, 1);
            }
            for (String queue : QUEUES) {
                assertNoApplicationDelivery(outsider, queue, Duration.ofSeconds(1));
            }
            byInstance.values().forEach(client -> assertThat(client.failure()).isNotDone());
            assertThat(outsider.failure()).isNotDone();
            assertThat(cluster.restRequests()).extracting(GatewayMessagingCluster.RestRequest::instanceId)
                    .containsExactlyInAnyOrderElementsOf(cluster.instanceIds());
            assertThat(cluster.upgrades()).allSatisfy(upgrade -> assertThat(upgrade.authorization()).isNull());

            String stopped = byInstance.keySet().iterator().next();
            StompTestClient disconnected = byInstance.get(stopped);
            String survivor = cluster.instanceIds().stream().filter(id -> !id.equals(stopped)).findFirst().orElseThrow();
            cluster.closeMessaging(stopped);
            assertThat(disconnected.closed().get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(cluster.activeInstanceIds()).containsExactly(survivor);
            try (StompTestClient reconnected = connected(freshToken(alice))) {
                QUEUES.forEach(reconnected::subscribe);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(cluster.subscribedInstances(alice, QUEUES)).containsExactly(survivor));
                waitForRelays(alice, List.of(reconnected, byInstance.get(survivor)));
                var refreshed = request(HttpMethod.GET, "/conversations/" + conversationId + "/messages", alice,
                        null, HttpStatus.OK);
                assertThat(refreshed.getHeaders().getCacheControl()).contains("no-store");
                assertThat(refreshed.getBody().path("items")).hasSize(2);
                assertThat(refreshed.getBody().path("items").get(0).path("id").asText())
                        .isEqualTo(sent.path("id").asText());
                assertThat(refreshed.getBody().path("items").get(0).path("text").asText()).isEqualTo(secondText);
                assertThat(refreshed.getBody().path("items").get(1).path("text").asText()).isEqualTo(firstText);
                assertUnread(reconnected, 0);
                assertUnread(byInstance.get(survivor), 0);
                request(HttpMethod.POST, "/conversations/" + conversationId + "/messages", bob,
                        Map.of("text", "Dopo la riconnessione: più messaggi"), HttpStatus.CREATED);
                assertPushes(reconnected, conversationId, "Dopo la riconnessione: più messaggi", 3, 1, 1);
                assertPushes(byInstance.get(survivor), conversationId, "Dopo la riconnessione: più messaggi", 3, 1, 1);
                assertThat(reconnected.failure()).isNotDone();
                assertThat(cluster.restRequests().getLast().instanceId()).isEqualTo(survivor);
            }
        } finally {
            closeClients(clients);
        }
    }

    private static void closeClients(List<StompTestClient> clients) {
        RuntimeException failure = null;
        for (StompTestClient client : clients.reversed()) {
            try {
                client.close();
            } catch (RuntimeException exception) {
                if (failure == null) failure = new IllegalStateException("Cannot close STOMP clients");
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String sub, Object body,
            HttpStatus expected) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(freshToken(sub));
        var response = rest.exchange(cluster.httpUrl() + path, method, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        assertThat(response.getHeaders().getFirst(GatewayMessagingCluster.INSTANCE_HEADER))
                .isIn(cluster.activeInstanceIds());
        return new ResponseEntity<>(json.readTree(response.getBody()), response.getHeaders(), response.getStatusCode());
    }

    private void refusedConnect(String url, StompHeaders headers) throws Exception {
        cluster.awaitWebSocketSlot();
        try (StompTestClient client = new StompTestClient()) {
            var connection = client.connect(url, headers);
            assertErrorAndClosed(client);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(connection).isCompletedExceptionally());
            assertThat(client.isConnected()).isFalse();
        }
    }

    private static void assertErrorAndClosed(StompTestClient client) throws Exception {
        assertThat(client.error(FRAME_TIMEOUT)).isNotNull();
        assertThat(client.closed().get(10, TimeUnit.SECONDS)).isNotNull();
    }

    private StompTestClient connected(String token) throws Exception {
        cluster.awaitWebSocketSlot();
        StompTestClient client = new StompTestClient();
        try {
            client.connect(cluster.wsUrl(), connectHeaders(token)).get(10, TimeUnit.SECONDS);
            return client;
        } catch (Exception failure) {
            client.close();
            throw failure;
        }
    }

    private static StompHeaders connectHeaders(String token) {
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }

    private String freshToken(String sub) {
        return tracked(TestJwtSupport.mintExpiring(sub, Instant.now().plusSeconds(1800)));
    }

    private String tracked(String token) {
        tokens.add(token);
        return token;
    }

    private void waitForRelays(String sub, List<StompTestClient> clients) {
        // Registry events precede broker subscriptions; a Redis round trip proves both are ready.
        await().atMost(FRAME_TIMEOUT).until(() -> {
            String probeId = UUID.randomUUID().toString();
            cluster.publishProbe(sub, probeId);
            boolean ready = true;
            for (StompTestClient client : clients) {
                ready &= receivesProbe(client, probeId);
            }
            return ready;
        });
    }

    private boolean receivesProbe(StompTestClient client, String probeId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(200).toNanos();
        while (System.nanoTime() < deadline) {
            StompTestClient.Frame frame = client.poll("/user/queue/unread",
                    Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            if (frame == null) return false;
            assertThat(frame.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            JsonNode body = json.readTree(frame.body());
            assertThat(body.has("probe")).isTrue();
            if (probeId.equals(body.path("probe").asText())) return true;
        }
        return false;
    }

    private void assertNoApplicationDelivery(StompTestClient client, String destination, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            StompTestClient.Frame frame = client.poll(destination,
                    Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            if (frame == null) return;
            assertThat(frame.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(json.readTree(frame.body()).has("probe"))
                    .as("unrelated user must receive no application frame on %s", destination).isTrue();
        }
    }

    private JsonNode frame(StompTestClient client, String destination) throws Exception {
        long deadline = System.nanoTime() + FRAME_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            StompTestClient.Frame frame = client.poll(destination,
                    Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            assertThat(frame).as("application frame on %s", destination).isNotNull();
            assertThat(frame.headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            JsonNode body = json.readTree(frame.body());
            if (!body.has("probe")) {
                return body;
            }
        }
        throw new AssertionError("No application frame on " + destination);
    }

    /** MESSAGE notifications coalesce per conversation, so the two unread counts diverge after the first message. */
    private void assertPushes(StompTestClient client, String conversationId, String text, long seq,
            int unreadMessages, int unreadNotifications) throws Exception {
        JsonNode message = frame(client, "/user/queue/messages");
        assertThat(message.path("type").asText()).isEqualTo("message");
        assertThat(message.path("conversationId").asText()).isEqualTo(conversationId);
        assertThat(message.path("message").path("seq").asLong()).isEqualTo(seq);
        assertThat(message.path("message").path("text").asText().getBytes(StandardCharsets.UTF_8))
                .containsExactly(text.getBytes(StandardCharsets.UTF_8));
        JsonNode notification = frame(client, "/user/queue/notifications");
        assertThat(notification.path("id").asText()).isNotBlank();
        assertThat(notification.path("type").asText()).isEqualTo("MESSAGE");
        assertUnread(client, unreadMessages, unreadNotifications);
    }

    private void assertUnread(StompTestClient client, int count) throws Exception {
        assertUnread(client, count, count);
    }

    private void assertUnread(StompTestClient client, int messages, int notifications) throws Exception {
        JsonNode unread = frame(client, "/user/queue/unread");
        assertThat(unread.path("messages").asInt()).isEqualTo(messages);
        assertThat(unread.path("notifications").asInt()).isEqualTo(notifications);
    }
}
