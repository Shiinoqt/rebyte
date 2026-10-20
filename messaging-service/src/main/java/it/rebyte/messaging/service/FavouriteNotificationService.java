package it.rebyte.messaging.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.FavouriteProjection;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.messaging.FavouriteEvent;
import it.rebyte.messaging.service.RelationshipOrdering.Decision;
import it.rebyte.messaging.service.RelationshipOrdering.State;

@Service
public class FavouriteNotificationService {

    private final MongoTemplate mongo;
    private final MongoTx transactions;
    private final NotificationWriter writer;
    private final UsernameLookup usernames;
    private final NotificationPushes pushes;

    public FavouriteNotificationService(MongoTemplate mongo, MongoTx transactions, NotificationWriter writer,
            UsernameLookup usernames, NotificationPushes pushes) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.writer = writer;
        this.usernames = usernames;
        this.pushes = pushes;
    }

    public void favourited(FavouriteEvent event) {
        String username = usernames.username(event.userSub());
        Notification notification = NotificationFactory.favourite(event.sellerSub(), event.userSub(), event.listingId(),
                snapshot(event, username));
        boolean inserted = transactions.execute(() -> {
            if (!applyState(event, true)) {
                return false;
            }
            return writer.upsertIfAbsent(notification);
        });
        if (inserted) {
            pushes.created(List.of(notification));
        }
    }

    public void unfavourited(FavouriteEvent event) {
        long deleted = transactions.execute(() -> {
            if (!applyState(event, false)) {
                return 0L;
            }
            Query target = Query.query(Criteria.where("recipientSub").is(event.sellerSub())
                    .and("type").is(NotificationType.FAVOURITE).and("favourite.favouriterSub").is(event.userSub())
                    .and("favourite.listingId").is(event.listingId()));
            return mongo.remove(target, Notification.class).getDeletedCount();
        });
        if (deleted > 0) {
            pushes.unreadChanged(event.sellerSub());
        }
    }

    private static Map<String, String> snapshot(FavouriteEvent event, String username) {
        Map<String, String> snapshot = new HashMap<>();
        if (username != null) {
            snapshot.put("actorUsername", username);
        }
        if (event.listingTitle() != null) {
            snapshot.put("listingTitle", event.listingTitle());
        }
        return snapshot;
    }

    private boolean applyState(FavouriteEvent event, boolean active) {
        String id = event.userSub() + "|" + event.listingId();
        FavouriteProjection current = mongo.findById(id, FavouriteProjection.class);
        State ordering = current == null ? null : new State(current.getLastEventId(), current.getLastEventTime(),
                current.getLastRelationshipSequence(), current.isActive());
        Decision decision = RelationshipOrdering.decide(ordering, event.eventId(), event.occurredAt(),
                event.relationshipSequence(), active);
        if (decision == Decision.CONFLICT) {
            throw new AmqpRejectAndDontRequeueException("Conflicting favourite sequence");
        }
        if (decision != Decision.APPLY) {
            return false;
        }
        // Keep the producer sequence high-water mark if a newer legacy payload arrives.
        Long sequence = event.relationshipSequence() != null ? event.relationshipSequence()
                : current == null ? null : current.getLastRelationshipSequence();
        mongo.save(new FavouriteProjection(id, event.userSub(), event.listingId(), event.sellerSub(), active,
                event.occurredAt(), event.occurredAt().toString(), event.eventId(), sequence));
        return true;
    }
}
