package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.repository.NotificationRepository;
import it.rebyte.messaging.service.NotificationFactory;
import it.rebyte.messaging.service.NotificationPushes;
import it.rebyte.messaging.support.MessagingTestBase;

@SpringBootTest
@ActiveProfiles("local-auth")
class RealtimePublisherIT extends MessagingTestBase {

    private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

    @Autowired RealtimePublisher publisher;
    @Autowired NotificationPushes pushes;
    @Autowired NotificationRepository notifications;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired ObjectMapper objectMapper;

    @Value("${rebyte.realtime.push-channel}")
    String channel;

    private RedisMessageListenerContainer listener;

    @AfterEach
    void stopListener() throws Exception {
        if (listener != null) {
            listener.stop();
            listener.destroy();
        }
    }

    @Test
    void pushesUtf8EnvelopeThenStoredNotificationAndUnreadSummary() throws Exception {
        startListenerAndWaitForSubscription();

        publisher.push("alice", RealtimePublisher.NOTIFICATIONS, Map.of("text", "Città é"));
        JsonNode directEnvelope = nextEnvelope();
        assertThat(directEnvelope.path("userSub").asText()).isEqualTo("alice");
        assertThat(directEnvelope.path("destination").asText()).isEqualTo(RealtimePublisher.NOTIFICATIONS);
        assertThat(objectMapper.readTree(directEnvelope.path("payload").asText()).path("text").asText())
                .isEqualTo("Città é");

        Notification notification = notifications.save(
                NotificationFactory.message("alice", "message-1", "conversation-1", 1,
                        Map.of("text", "Città é")));
        pushes.created(java.util.List.of(notification));

        JsonNode notificationEnvelope = nextEnvelope();
        assertThat(notificationEnvelope.path("userSub").asText()).isEqualTo("alice");
        assertThat(notificationEnvelope.path("destination").asText()).isEqualTo(RealtimePublisher.NOTIFICATIONS);
        JsonNode notificationPayload = objectMapper.readTree(notificationEnvelope.path("payload").asText());
        assertThat(notificationPayload.path("id").asText()).isEqualTo(notification.getId());
        assertThat(notificationPayload.path("target").path("messageId").asText()).isEqualTo("message-1");

        JsonNode unreadEnvelope = nextEnvelope();
        assertThat(unreadEnvelope.path("userSub").asText()).isEqualTo("alice");
        assertThat(unreadEnvelope.path("destination").asText()).isEqualTo(RealtimePublisher.UNREAD);
        JsonNode unreadPayload = objectMapper.readTree(unreadEnvelope.path("payload").asText());
        assertThat(unreadPayload.path("notifications").asLong()).isEqualTo(1);
        assertThat(unreadPayload.path("messages").asLong()).isZero();
    }

    @Test
    void pushesOneUnreadSummaryForEachDistinctRecipient() throws Exception {
        startListenerAndWaitForSubscription();
        String alice = "alice-recipient-summary";
        String bob = "bob-recipient-summary";

        List<Notification> created = notifications.saveAll(List.of(
                NotificationFactory.message(alice, "message-a1", "conversation-a", 1, Map.of()),
                NotificationFactory.message(alice, "message-a2", "conversation-a2", 1, Map.of()),
                NotificationFactory.message(bob, "message-b1", "conversation-b", 1, Map.of())));
        pushes.created(created);

        List<JsonNode> envelopes = List.of(nextEnvelope(), nextEnvelope(), nextEnvelope(), nextEnvelope(), nextEnvelope());
        assertThat(envelopes.stream()
                .filter(envelope -> RealtimePublisher.NOTIFICATIONS.equals(envelope.path("destination").asText())))
                .hasSize(3);
        assertThat(envelopes.stream()
                .filter(envelope -> RealtimePublisher.UNREAD.equals(envelope.path("destination").asText())))
                .hasSize(2);

        JsonNode aliceUnread = unreadEnvelopeFor(envelopes, alice);
        assertThat(aliceUnread.path("notifications").asLong()).isEqualTo(2);
        assertThat(aliceUnread.path("messages").asLong()).isZero();

        JsonNode bobUnread = unreadEnvelopeFor(envelopes, bob);
        assertThat(bobUnread.path("notifications").asLong()).isEqualTo(1);
        assertThat(bobUnread.path("messages").asLong()).isZero();
    }

    private JsonNode unreadEnvelopeFor(List<JsonNode> envelopes, String userSub) throws Exception {
        JsonNode envelope = envelopes.stream()
                .filter(candidate -> RealtimePublisher.UNREAD.equals(candidate.path("destination").asText()))
                .filter(candidate -> userSub.equals(candidate.path("userSub").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing unread envelope for " + userSub));
        return objectMapper.readTree(envelope.path("payload").asText());
    }

    private void startListenerAndWaitForSubscription() throws Exception {
        listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(redisConnectionFactory);
        listener.addMessageListener((message, pattern) -> messages.offer(
                new String(message.getBody(), StandardCharsets.UTF_8)), ChannelTopic.of(channel));
        listener.afterPropertiesSet();
        listener.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            publisher.push("probe", RealtimePublisher.PRESENCE, Map.of("ready", true));
            if (messages.poll(100, TimeUnit.MILLISECONDS) != null) {
                return;
            }
        }
        throw new AssertionError("Redis subscriber did not receive the readiness probe");
    }

    private JsonNode nextEnvelope() throws Exception {
        String raw = messages.poll(5, TimeUnit.SECONDS);
        assertThat(raw).as("published Redis envelope").isNotNull();
        return objectMapper.readTree(raw);
    }
}
