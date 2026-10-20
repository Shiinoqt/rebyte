package it.rebyte.messaging.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.error.ConversationNotFoundException;

/**
 * The single append path for chat messages and offer cards. Call inside MongoTx so counters,
 * message, notification and the caller's email job commit together (D-46, no message.sent hop).
 */
@Component
public class ConversationWriter {

    private final MongoTemplate mongo;
    private final NotificationWriter notifications;

    public ConversationWriter(MongoTemplate mongo, NotificationWriter notifications) {
        this.mongo = mongo;
        this.notifications = notifications;
    }

    public Appended append(String conversationId, MessageDraft draft, Map<String, String> notificationSnapshot) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(mongo.getMongoDatabaseFactory())) {
            throw new IllegalStateException("Conversation append requires a Mongo transaction");
        }
        Conversation existing = mongo.findById(conversationId, Conversation.class);
        if (existing == null || !existing.isParticipant(draft.senderSub())
                || !existing.otherParticipant(draft.senderSub()).equals(draft.recipientSub())) {
            throw new ConversationNotFoundException();
        }
        String text = draft.text() == null ? null : MessageTextRules.normalize(draft.text());
        List<String> images = draft.imageKeys() == null || draft.imageKeys().isEmpty()
                ? null : List.copyOf(draft.imageKeys());
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Conversation.LastMessage last = new Conversation.LastMessage(id, draft.senderSub(), draft.kind(),
                draft.kind() == MessageKind.CHAT ? MessageTextRules.preview(text) : null,
                images == null ? 0 : images.size(), now);
        Update update = new Update().inc("messageSeq", 1L)
                .inc(Conversation.unreadField(existing, draft.recipientSub()), 1L)
                .set("lastMessageAt", now).set("lastMessage", last);
        Conversation changed = mongo.findAndModify(Query.query(Criteria.where("_id").is(conversationId)), update,
                FindAndModifyOptions.options().returnNew(true), Conversation.class);
        if (changed == null) {
            throw new ConversationNotFoundException();
        }
        Message message = mongo.insert(new Message(id, conversationId, changed.getMessageSeq(), draft.senderSub(),
                draft.recipientSub(), draft.kind(), text, images, draft.offer(), draft.sourceEventId(), now, false, null));
        Notification notification = null;
        if (notificationSnapshot != null) {
            Notification candidate = NotificationFactory.message(draft.recipientSub(), id, conversationId,
                    message.getSeq(), notificationSnapshot);
            notification = notifications.coalesceMessage(candidate, message.getCreatedAt());
        }
        return new Appended(message, notification, changed);
    }

    public record Appended(Message message, Notification notification, Conversation conversation) {
    }
}
