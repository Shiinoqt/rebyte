package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.dto.NotificationView;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.service.NotificationFactory;
import it.rebyte.messaging.domain.OrderNotificationEvent;

class RealtimePublisherTest {

    @Test
    void redisFailureDoesNotEscapeThePushCall() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new RedisConnectionFailureException("unavailable"))
                .when(redis).convertAndSend(eq("rebyte:messaging:push"), any());
        RealtimePublisher publisher = new RealtimePublisher(redis, new ObjectMapper(), "rebyte:messaging:push");

        assertThatCode(() -> publisher.push("alice", RealtimePublisher.NOTIFICATIONS, Map.of("text", "hello")))
                .doesNotThrowAnyException();

        verify(redis).convertAndSend(eq("rebyte:messaging:push"), any());
    }

    @Test
    void notificationViewFlattensEachTargetAndCopiesSnapshot() {
        Instant instant = Instant.parse("2026-01-02T03:04:05Z");
        Map<String, String> snapshot = Map.of("actor", "Alice");

        assertView(NotificationFactory.message("r", "m", "c", 7, snapshot), NotificationType.MESSAGE,
                Map.of("messageId", "m", "conversationId", "c", "seq", "7"), snapshot);
        assertView(NotificationFactory.favourite("r", "f", "l", snapshot), NotificationType.FAVOURITE,
                Map.of("favouriterSub", "f", "listingId", "l"), snapshot);
        assertView(NotificationFactory.priceDrop("r", "l", instant, snapshot), NotificationType.PRICE_DROP,
                Map.of("listingId", "l", "instant", instant.toString()), snapshot);
        assertView(NotificationFactory.release("r", "l", "3", snapshot), NotificationType.RELEASE,
                Map.of("listingId", "l", "versionNumber", "3"), snapshot);
        assertView(NotificationFactory.review("r", "review", snapshot), NotificationType.REVIEW,
                Map.of("reviewId", "review"), snapshot);
        assertView(NotificationFactory.follow("r", "f", snapshot), NotificationType.FOLLOW,
                Map.of("followerSub", "f"), snapshot);
        assertView(NotificationFactory.offerReceived("r", "offer", snapshot), NotificationType.OFFER_RECEIVED,
                Map.of("offerId", "offer"), snapshot);
        assertView(NotificationFactory.offerAccepted("r", "offer", snapshot), NotificationType.OFFER_ACCEPTED,
                Map.of("offerId", "offer"), snapshot);
        assertView(NotificationFactory.order("r", "order", OrderNotificationEvent.SHIPPED, "event", snapshot),
                NotificationType.ORDER, Map.of("orderId", "order", "event", "SHIPPED"), snapshot);
    }

    private static void assertView(it.rebyte.messaging.domain.Notification notification, NotificationType type,
            Map<String, String> target, Map<String, String> snapshot) {
        NotificationView view = NotificationView.from(notification);
        org.assertj.core.api.Assertions.assertThat(view.type()).isEqualTo(type);
        org.assertj.core.api.Assertions.assertThat(view.target()).isEqualTo(target);
        org.assertj.core.api.Assertions.assertThat(view.snapshot()).isEqualTo(snapshot);
    }
}
