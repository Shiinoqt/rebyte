package it.rebyte.messaging.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bson.Document;
import org.springframework.data.mongodb.BulkOperationException;
import org.springframework.data.mongodb.core.BulkOperations.BulkMode;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.result.UpdateResult;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;

/**
 * Idempotent notification writes keyed by {@code (recipientSub, dedupKey)}.
 *
 * <p>A plain insert that hits the unique index aborts an enclosing Mongo
 * transaction, so {@link #upsertIfAbsent} is safe to call inside one: an
 * existing key matches instead of failing. {@link #insertAllIgnoringDuplicates}
 * must stay outside a transaction for the opposite reason: one duplicate would
 * abort every row of the batch.
 */
@Component
public class NotificationWriter {

    private static final String COLLECTION = "notification";
    private static final int DUPLICATE_KEY = 11000;

    private final MongoTemplate mongoTemplate;

    public NotificationWriter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** Returns true when the notification was stored, false when its key already existed. */
    public boolean upsertIfAbsent(Notification notification) {
        Document converted = new Document();
        mongoTemplate.getConverter().write(notification, converted);

        Query query = Query.query(Criteria.where("recipientSub").is(notification.getRecipientSub())
                .and("dedupKey").is(notification.getDedupKey()));
        Update update = new Update();
        // The upsert already copies the equality fields of the query into the inserted document.
        converted.forEach((field, value) -> {
            if (!field.equals("recipientSub") && !field.equals("dedupKey")) {
                update.setOnInsert(field, value);
            }
        });

        UpdateResult result = mongoTemplate.upsert(query, update, COLLECTION);
        return result.getUpsertedId() != null;
    }

    /** Retargets the unread row or opens a new one; call inside the transaction that appended the message. */
    public Notification coalesceMessage(Notification notification, Instant messageInstant) {
        Document converted = new Document();
        mongoTemplate.getConverter().write(notification, converted);

        Query query = Query.query(Criteria.where("recipientSub").is(notification.getRecipientSub())
                .and("type").is(NotificationType.MESSAGE).and("read").is(false)
                .and("message.conversationId").is(notification.getMessage().conversationId()));
        Update update = new Update().set("message", notification.getMessage())
                .set("snapshot", notification.getSnapshot()).set("createdAt", messageInstant);
        // The id and dedup key belong to the first message of this unread notification.
        converted.forEach((field, value) -> {
            if (!field.equals("message") && !field.equals("snapshot") && !field.equals("createdAt")) {
                update.setOnInsert(field, value);
            }
        });
        return mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true),
                Notification.class);
    }

    /** Stores the rows whose key is new and returns exactly those rows. */
    public List<Notification> insertAllIgnoringDuplicates(List<Notification> notifications) {
        if (notifications.isEmpty()) {
            return List.of();
        }
        Set<String> existing = existingKeys(notifications);
        List<Notification> fresh = new ArrayList<>();
        for (Notification notification : notifications) {
            if (!existing.contains(key(notification))) {
                fresh.add(notification);
            }
        }
        if (fresh.isEmpty()) {
            return List.of();
        }

        try {
            mongoTemplate.bulkOps(BulkMode.UNORDERED, Notification.class).insert(fresh).execute();
            return fresh;
        } catch (BulkOperationException failure) {
            Set<Integer> rejected = new HashSet<>();
            for (BulkWriteError error : failure.getErrors()) {
                if (error.getCode() != DUPLICATE_KEY) {
                    throw failure;
                }
                rejected.add(error.getIndex());
            }
            List<Notification> stored = new ArrayList<>();
            for (int index = 0; index < fresh.size(); index++) {
                if (!rejected.contains(index)) {
                    stored.add(fresh.get(index));
                }
            }
            return stored;
        }
    }

    private Set<String> existingKeys(List<Notification> notifications) {
        Set<String> recipients = new HashSet<>();
        Set<String> dedupKeys = new HashSet<>();
        for (Notification notification : notifications) {
            recipients.add(notification.getRecipientSub());
            dedupKeys.add(notification.getDedupKey());
        }
        Query query = Query.query(Criteria.where("recipientSub").in(recipients).and("dedupKey").in(dedupKeys));
        query.fields().include("recipientSub", "dedupKey");
        Set<String> existing = new HashSet<>();
        for (Document stored : mongoTemplate.find(query, Document.class, COLLECTION)) {
            existing.add(stored.getString("recipientSub") + '\u0000' + stored.getString("dedupKey"));
        }
        return existing;
    }

    private static String key(Notification notification) {
        return notification.getRecipientSub() + '\u0000' + notification.getDedupKey();
    }
}
