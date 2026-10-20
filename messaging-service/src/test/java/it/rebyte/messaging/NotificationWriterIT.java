package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.domain.OrderNotificationEvent;
import it.rebyte.messaging.service.MongoTx;
import it.rebyte.messaging.service.NotificationFactory;
import it.rebyte.messaging.service.NotificationWriter;
import it.rebyte.messaging.support.MessagingTestBase;

@SpringBootTest
@ActiveProfiles("local-auth")
class NotificationWriterIT extends MessagingTestBase {

    private static final Map<String, String> SNAPSHOT = Map.of("title", "Keyboard");

    @Autowired NotificationWriter writer;
    @Autowired MongoTx mongoTx;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), Notification.class);
        mongo.remove(new Query(), "writer_probe");
    }

    @Test
    void sameRecipientAndKeyIsStoredOnce() {
        Notification first = NotificationFactory.review("seller", "r1", SNAPSHOT);
        Notification redelivered = NotificationFactory.review("seller", "r1", SNAPSHOT);

        assertThat(writer.upsertIfAbsent(first)).isTrue();
        assertThat(writer.upsertIfAbsent(redelivered)).isFalse();

        List<Notification> stored = mongo.findAll(Notification.class);
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getId()).isEqualTo(first.getId());
    }

    @Test
    void upsertRoundTripsTypeTargetSnapshotAndDefaults() {
        Notification order = NotificationFactory.order("buyer", "ord1", OrderNotificationEvent.SHIPPED, "evt1", SNAPSHOT);
        writer.upsertIfAbsent(order);

        Notification stored = mongo.findById(order.getId(), Notification.class);

        assertThat(stored).isNotNull();
        assertThat(stored.getType()).isEqualTo(NotificationType.ORDER);
        assertThat(stored.getRecipientSub()).isEqualTo("buyer");
        assertThat(stored.getDedupKey()).isEqualTo("ORDER:evt1");
        assertThat(stored.getOrder().orderId()).isEqualTo("ord1");
        assertThat(stored.getOrder().event()).isEqualTo(OrderNotificationEvent.SHIPPED);
        assertThat(stored.getSnapshot()).containsEntry("title", "Keyboard");
        assertThat(stored.isRead()).isFalse();
        assertThat(stored.getCreatedAt()).isEqualTo(order.getCreatedAt());
        assertThat(stored.getExpiresAt()).isNull();
    }

    @Test
    void sameKeyForDifferentRecipientsIsStoredForBoth() {
        assertThat(writer.upsertIfAbsent(NotificationFactory.offerReceived("a", "o1", SNAPSHOT))).isTrue();
        assertThat(writer.upsertIfAbsent(NotificationFactory.offerReceived("b", "o1", SNAPSHOT))).isTrue();

        assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(2);
    }

    @Test
    void existingKeyInsideTransactionDoesNotAbortIt() {
        writer.upsertIfAbsent(NotificationFactory.review("seller", "r1", SNAPSHOT));

        boolean inserted = mongoTx.execute(() -> {
            boolean result = writer.upsertIfAbsent(NotificationFactory.review("seller", "r1", SNAPSHOT));
            mongo.insert(new Document("marker", "committed"), "writer_probe");
            return result;
        });

        assertThat(inserted).isFalse();
        assertThat(mongo.count(new Query(Criteria.where("marker").is("committed")), "writer_probe")).isEqualTo(1);
        assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(1);
    }

    @Test
    void newKeyInsideTransactionIsCommittedWithTheOtherWrite() {
        mongoTx.run(() -> {
            writer.upsertIfAbsent(NotificationFactory.review("seller", "r2", SNAPSHOT));
            mongo.insert(new Document("marker", "committed"), "writer_probe");
        });

        assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(1);
        assertThat(mongo.count(new Query(), "writer_probe")).isEqualTo(1);
    }

    @Test
    void bulkInsertReturnsOnlyNewRows() {
        Notification existingA = NotificationFactory.follow("followed", "f1", SNAPSHOT);
        Notification existingB = NotificationFactory.follow("followed", "f2", SNAPSHOT);
        writer.upsertIfAbsent(existingA);
        writer.upsertIfAbsent(existingB);

        Notification dupA = NotificationFactory.follow("followed", "f1", SNAPSHOT);
        Notification dupB = NotificationFactory.follow("followed", "f2", SNAPSHOT);
        Notification newA = NotificationFactory.follow("followed", "f3", SNAPSHOT);
        Notification newB = NotificationFactory.follow("followed", "f4", SNAPSHOT);
        Notification newC = NotificationFactory.follow("followed", "f5", SNAPSHOT);

        List<Notification> stored = writer.insertAllIgnoringDuplicates(List.of(dupA, newA, dupB, newB, newC));

        assertThat(stored).extracting(Notification::getId).containsExactly(newA.getId(), newB.getId(), newC.getId());
        assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(5);
    }

    @Test
    void bulkInsertToleratesDuplicateKeysRaisedByTheServer() {
        Notification first = NotificationFactory.follow("followed", "f1", SNAPSHOT);
        Notification sameKey = NotificationFactory.follow("followed", "f1", SNAPSHOT);
        Notification other = NotificationFactory.follow("followed", "f2", SNAPSHOT);

        List<Notification> stored = writer.insertAllIgnoringDuplicates(List.of(first, sameKey, other));

        assertThat(stored).extracting(Notification::getId).containsExactly(first.getId(), other.getId());
        assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(2);
    }

    @Test
    void bulkInsertOfNothingReturnsNothing() {
        assertThat(writer.insertAllIgnoringDuplicates(List.of())).isEmpty();
    }

    @Test
    void bulkInsertRerunStoresNothingTwice() {
        List<Notification> batch = List.of(NotificationFactory.release("a", "l1", "2", SNAPSHOT),
                NotificationFactory.release("b", "l1", "2", SNAPSHOT));
        assertThat(writer.insertAllIgnoringDuplicates(batch)).hasSize(2);

        List<Notification> rerun = List.of(NotificationFactory.release("a", "l1", "2", SNAPSHOT),
                NotificationFactory.release("b", "l1", "2", SNAPSHOT));

        assertThat(writer.insertAllIgnoringDuplicates(rerun)).isEmpty();
        assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(2);
    }
}
