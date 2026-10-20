package it.rebyte.messaging.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;

/**
 * Marks a reader's received messages read up to the newest seq the reader was actually shown, so a message
 * that arrives between the page fetch and this write stays unread (Pitfall 6). Counters only ever move by
 * the exact modified count.
 */
@Service
public class ReadService {

    private final MongoTemplate mongo;
    private final MongoTx tx;
    private final NotificationPushes pushes;
    private final Duration retention;

    public ReadService(MongoTemplate mongo, MongoTx tx, NotificationPushes pushes,
            @Value("${rebyte.notifications.read-retention}") Duration retention) {
        this.mongo = mongo;
        this.tx = tx;
        this.pushes = pushes;
        this.retention = retention;
    }

    public long markRead(String readerSub, Conversation conversation, long maxSeq) {
        long[] changes = tx.execute(() -> {
            Instant readAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            long messages = mongo.updateMulti(Query.query(Criteria.where("conversationId").is(conversation.getId())
                            .and("recipientSub").is(readerSub).and("read").is(false).and("seq").lte(maxSeq)),
                    new Update().set("read", true).set("readAt", readAt), Message.class).getModifiedCount();
            Update conversationUpdate = new Update()
                    .unset(Conversation.lastEmailField(conversation, readerSub))
                    .inc(emailGenerationField(conversation, readerSub), 1L);
            if (messages > 0) {
                conversationUpdate.inc(Conversation.unreadField(conversation, readerSub), -messages);
            }
            mongo.updateFirst(Query.query(Criteria.where("_id").is(conversation.getId())), conversationUpdate,
                    Conversation.class);
            long notifications = mongo.updateMulti(Query.query(Criteria.where("recipientSub").is(readerSub)
                            .and("type").is(NotificationType.MESSAGE)
                            .and("message.conversationId").is(conversation.getId())
                            .and("message.seq").lte(maxSeq).and("read").is(false)),
                    new Update().set("read", true).set("readAt", readAt).set("expiresAt", readAt.plus(retention)),
                    Notification.class).getModifiedCount();
            return new long[] {messages, notifications};
        });
        if (changes[0] > 0 || changes[1] > 0) {
            pushes.unreadChanged(readerSub);
        }
        return changes[0];
    }

    public static long newestSeq(List<Message> page) {
        return page.stream().mapToLong(Message::getSeq).max().orElse(0);
    }

    private static String emailGenerationField(Conversation conversation, String sub) {
        return conversation.getBuyerSub().equals(sub) ? "buyerEmailGeneration" : "sellerEmailGeneration";
    }
}
