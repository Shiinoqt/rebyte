package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.domain.OrderNotificationEvent;
import it.rebyte.messaging.service.NotificationFactory;

class NotificationFactoryTest {

    private static final List<String> TARGET_GETTERS = List.of("getMessage", "getFavourite", "getPriceDrop",
            "getRelease", "getReviewId", "getFollow", "getOfferId", "getOrder");

    private static final Map<String, String> SNAPSHOT = Map.of("title", "Keyboard");

    private record Case(Notification notification, NotificationType type, String dedupKey, String populatedGetter) {
    }

    private static List<Case> cases() {
        Instant instant = Instant.parse("2026-03-01T10:15:30.123456789Z");
        return List.of(
                new Case(NotificationFactory.message("rcpt", "m1", "c1", 7L, SNAPSHOT),
                        NotificationType.MESSAGE, "MESSAGE:m1", "getMessage"),
                new Case(NotificationFactory.favourite("seller", "fan", "l1", SNAPSHOT),
                        NotificationType.FAVOURITE, "FAVOURITE:fan:l1", "getFavourite"),
                new Case(NotificationFactory.priceDrop("rcpt", "l1", instant, SNAPSHOT),
                        NotificationType.PRICE_DROP, "PRICE_DROP:l1:" + instant.toEpochMilli(), "getPriceDrop"),
                new Case(NotificationFactory.release("rcpt", "l1", "3", SNAPSHOT),
                        NotificationType.RELEASE, "RELEASE:l1:3", "getRelease"),
                new Case(NotificationFactory.review("seller", "r1", SNAPSHOT),
                        NotificationType.REVIEW, "REVIEW:r1", "getReviewId"),
                new Case(NotificationFactory.follow("followed", "follower", SNAPSHOT),
                        NotificationType.FOLLOW, "FOLLOW:follower", "getFollow"),
                new Case(NotificationFactory.offerReceived("seller", "o1", SNAPSHOT),
                        NotificationType.OFFER_RECEIVED, "OFFER_RECEIVED:o1", "getOfferId"),
                new Case(NotificationFactory.offerAccepted("buyer", "o1", SNAPSHOT),
                        NotificationType.OFFER_ACCEPTED, "OFFER_ACCEPTED:o1", "getOfferId"),
                new Case(NotificationFactory.order("buyer", "ord1", OrderNotificationEvent.PAID, "evt1", SNAPSHOT),
                        NotificationType.ORDER, "ORDER:evt1", "getOrder"));
    }

    @Test
    void everyTypeHasExactlyTheTargetItRequires() throws Exception {
        for (Case c : cases()) {
            Notification n = c.notification();
            assertThat(n.getType()).as(c.type().name()).isEqualTo(c.type());
            assertThat(n.getDedupKey()).as(c.type().name()).isEqualTo(c.dedupKey());

            int populated = 0;
            for (String getter : TARGET_GETTERS) {
                Method method = Notification.class.getMethod(getter);
                boolean present = method.invoke(n) != null;
                if (present) {
                    populated++;
                    assertThat(getter).as(c.type().name()).isEqualTo(c.populatedGetter());
                }
            }
            assertThat(populated).as(c.type().name()).isEqualTo(1);
        }
    }

    @Test
    void everyNotificationTypeIsCoveredByAFactoryMethod() {
        assertThat(cases().stream().map(Case::type))
                .containsExactlyInAnyOrder(NotificationType.values());
    }

    @Test
    void newNotificationsAreUnreadWithoutRetentionAndHaveFreshIds() {
        List<Case> first = cases();
        List<Case> second = cases();
        for (int i = 0; i < first.size(); i++) {
            Notification n = first.get(i).notification();
            assertThat(n.isRead()).isFalse();
            assertThat(n.getReadAt()).isNull();
            assertThat(n.getExpiresAt()).isNull();
            assertThat(n.getId()).isNotBlank().isNotEqualTo(second.get(i).notification().getId());
        }
    }

    @Test
    void createdAtHasMillisecondPrecision() {
        for (Case c : cases()) {
            assertThat(c.notification().getCreatedAt().getNano() % 1_000_000).isZero();
        }
    }

    @Test
    void priceDropKeyAndTargetUseTheMillisecondInstant() {
        Instant withNanos = Instant.parse("2026-03-01T10:15:30.123987654Z");

        Notification n = NotificationFactory.priceDrop("rcpt", "l1", withNanos, SNAPSHOT);

        assertThat(n.getDedupKey()).isEqualTo("PRICE_DROP:l1:" + withNanos.toEpochMilli());
        assertThat(n.getPriceDrop().instant()).isEqualTo(Instant.ofEpochMilli(withNanos.toEpochMilli()));
    }

    @Test
    void snapshotDropsNullValuesAndIsUnmodifiable() {
        Map<String, String> raw = new HashMap<>();
        raw.put("title", "Keyboard");
        raw.put("price", null);

        Notification n = NotificationFactory.review("seller", "r1", raw);
        raw.put("late", "x");

        assertThat(n.getSnapshot()).containsExactly(Map.entry("title", "Keyboard"));
        assertThatThrownBy(() -> n.getSnapshot().put("a", "b")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullSnapshotBecomesEmpty() {
        assertThat(NotificationFactory.review("seller", "r1", null).getSnapshot()).isEmpty();
    }

    @Test
    void blankIdentifiersAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> NotificationFactory.message(" ", "m", "c", 1, SNAPSHOT));
        assertThatIllegalArgumentException().isThrownBy(() -> NotificationFactory.message("r", "", "c", 1, SNAPSHOT));
        assertThatIllegalArgumentException().isThrownBy(() -> NotificationFactory.favourite("s", null, "l", SNAPSHOT));
        assertThatIllegalArgumentException().isThrownBy(() -> NotificationFactory.review("s", " ", SNAPSHOT));
        assertThatIllegalArgumentException().isThrownBy(() -> NotificationFactory.follow("", "f", SNAPSHOT));
        assertThatIllegalArgumentException().isThrownBy(() -> NotificationFactory.offerReceived("s", "", SNAPSHOT));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NotificationFactory.order("r", "o", OrderNotificationEvent.PAID, " ", SNAPSHOT));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NotificationFactory.order("r", "o", null, "e", SNAPSHOT));
    }

    @Test
    void orderTargetCarriesTheOrderAndEvent() {
        for (OrderNotificationEvent event : OrderNotificationEvent.values()) {
            Notification n = NotificationFactory.order("buyer", "ord1", event, "evt-" + event, SNAPSHOT);

            assertThat(n.getOrder().orderId()).isEqualTo("ord1");
            assertThat(n.getOrder().event()).isEqualTo(event);
        }
    }

    @Test
    void markReadSetsReadAtAndExpiry() {
        Notification n = NotificationFactory.review("seller", "r1", SNAPSHOT);
        Instant readAt = Instant.parse("2026-03-01T10:00:00Z");

        n.markRead(readAt, java.time.Duration.ofDays(30));

        assertThat(n.isRead()).isTrue();
        assertThat(n.getReadAt()).isEqualTo(readAt);
        assertThat(n.getExpiresAt()).isEqualTo(readAt.plus(java.time.Duration.ofDays(30)));
    }
}
