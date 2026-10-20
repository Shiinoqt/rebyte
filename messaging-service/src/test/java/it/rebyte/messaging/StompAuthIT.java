package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.StompTestClient;
import it.rebyte.messaging.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
@TestPropertySource(properties = {
        "rebyte.realtime.expiry-sweep-ms=500",
        "rebyte.realtime.time-to-first-message-ms=2000",
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *"
})
@ExtendWith(OutputCaptureExtension.class)
class StompAuthIT extends MessagingTestBase {

    @Value("${local.server.port}") int port;
    @Autowired SimpUserRegistry users;
    private final List<String> tokens = new ArrayList<>();

    @AfterEach
    void tokensNeverAppearInLogs(CapturedOutput output) {
        for (String token : tokens) {
            assertThat(output.getAll()).doesNotContain(token, "Bearer " + token);
        }
    }

    @Test
    void missingAuthorizationGetsErrorAndDisconnect() throws Exception {
        refusedConnect(url(), new StompHeaders());
    }

    @Test
    void emptyBearerGetsErrorAndDisconnect() throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer ");
        refusedConnect(url(), headers);
    }

    @Test
    void queryStringTokenCannotAuthenticate() throws Exception {
        String token = token("query-" + UUID.randomUUID());
        refusedConnect(url() + "?access_token=" + token, new StompHeaders());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBSCRIBE\nid:0\ndestination:/user/queue/messages\n\n\u0000",
            "SEND\ndestination:/app/conversations/c1/typing\n\n{}\u0000",
            "UNSUBSCRIBE\nid:0\n\n\u0000",
            "ACK\nid:message-1\n\n\u0000", "NACK\nid:message-1\n\n\u0000",
            "BEGIN\ntransaction:tx-1\n\n\u0000", "COMMIT\ntransaction:tx-1\n\n\u0000",
            "ABORT\ntransaction:tx-1\n\n\u0000"})
    void clientFramesBeforeConnectGetErrorAndCloseSocket(String frame) throws Exception {
        try (RawSocket socket = new RawSocket(url())) {
            socket.session.sendMessage(new TextMessage(frame));
            assertThat(socket.received.get(5, TimeUnit.SECONDS)).startsWith("ERROR\n");
            assertThat(socket.closed.get(5, TimeUnit.SECONDS)).isNotNull();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(socket.session.isOpen()).isFalse());
        }
    }

    @Test
    void disconnectBeforeConnectClosesSocket() throws Exception {
        try (RawSocket socket = new RawSocket(url())) {
            socket.session.sendMessage(new TextMessage("DISCONNECT\nreceipt:disconnect-1\n\n\u0000"));
            assertThat(socket.received.get(5, TimeUnit.SECONDS)).startsWith("ERROR\n");
            assertThat(socket.closed.get(5, TimeUnit.SECONDS)).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/anything", "/queue/messages", "/user/queue/other",
            "/user/bob/queue/messages"})
    void authenticatedForeignSubscriptionIsRefused(String destination) throws Exception {
        try (StompTestClient client = connected(token("subscriber-" + UUID.randomUUID()))) {
            client.subscribe(destination);
            assertErrorAndClosed(client);
        }
    }

    @Test
    void sendOutsideTypingIsRefused() throws Exception {
        try (StompTestClient client = connected(token("sender-" + UUID.randomUUID()))) {
            client.send("/app/anything-else", new byte[0]);
            assertErrorAndClosed(client);
        }
    }

    @Test
    void typingSendIsAcceptedAndSessionRemainsUsable() throws Exception {
        String sub = "typing-" + UUID.randomUUID();
        try (StompTestClient client = connected(token(sub))) {
            client.send("/app/conversations/c1/typing", new byte[0]);
            client.subscribe("/user/queue/typing");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(users.getUser(sub)).isNotNull();
                assertThat(users.getUser(sub).getSessions()).singleElement().satisfies(session ->
                        assertThat(session.getSubscriptions()).singleElement().satisfies(subscription ->
                                assertThat(subscription.getDestination()).isEqualTo("/user/queue/typing")));
            });
            assertThat(client.error(Duration.ofMillis(500))).isNull();
            assertThat(client.closed()).isNotDone();
            assertThat(client.isConnected()).isTrue();
        }
    }

    @Test
    void passiveSubscriberClosesWithPolicyViolationWhenTokenExpires() throws Exception {
        String token = TestJwtSupport.mintExpiring("carol", Instant.now().plusSeconds(2));
        tokens.add(token);
        try (StompTestClient client = connected(token)) {
            client.subscribe("/user/queue/messages");
            CloseStatus status = client.closed().get(5, TimeUnit.SECONDS);
            assertThat(status.getCode()).isEqualTo(1008);
            assertThat(status.getReason()).isEqualTo("token expired");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(users.getUser("carol")).isNull());
        }
    }

    @Test
    void silentSocketClosesAfterTheConnectDeadline() throws Exception {
        try (RawSocket socket = new RawSocket(url())) {
            long started = System.nanoTime();
            assertThat(socket.closed.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isBetween(Duration.ofMillis(1500), Duration.ofSeconds(5));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(socket.session.isOpen()).isFalse());
        }
    }

    private void refusedConnect(String url, StompHeaders headers) throws Exception {
        try (StompTestClient client = new StompTestClient()) {
            CompletableFuture<?> connection = client.connect(url, headers);
            assertErrorAndClosed(client);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(connection.isCompletedExceptionally()).isTrue());
            assertThat(client.isConnected()).isFalse();
        }
    }

    private void assertErrorAndClosed(StompTestClient client) throws Exception {
        assertThat(client.error(Duration.ofSeconds(5))).isNotNull();
        assertThat(client.closed().get(5, TimeUnit.SECONDS)).isNotNull();
    }

    private StompTestClient connected(String token) throws Exception {
        StompTestClient client = new StompTestClient();
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", "Bearer " + token);
        try {
            client.connect(url(), headers).get(5, TimeUnit.SECONDS);
            return client;
        } catch (Exception failure) {
            client.close();
            throw failure;
        }
    }

    private String token(String sub) {
        String token = TestJwtSupport.mint(sub);
        tokens.add(token);
        return token;
    }

    private String url() {
        return "ws://localhost:" + port + "/ws";
    }

    private static final class RawSocket implements AutoCloseable {
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        private final CompletableFuture<String> received = new CompletableFuture<>();
        private final WebSocketSession session;

        private RawSocket(String url) throws Exception {
            session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
                    received.complete(message.getPayload());
                }

                @Override
                public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
                    closed.complete(status);
                }
            }, new WebSocketHttpHeaders(), URI.create(url)).get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) {
                session.close();
            }
        }
    }
}
