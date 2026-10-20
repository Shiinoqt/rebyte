package it.rebyte.messaging.service;

import java.util.List;
import java.util.Map;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.FollowState;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.messaging.FollowEvent;
import it.rebyte.messaging.service.RelationshipOrdering.Decision;
import it.rebyte.messaging.service.RelationshipOrdering.State;

@Service
public class FollowNotificationService {

    private final MongoTemplate mongo;
    private final MongoTx transactions;
    private final NotificationWriter writer;
    private final UsernameLookup usernames;
    private final NotificationPushes pushes;

    public FollowNotificationService(MongoTemplate mongo, MongoTx transactions, NotificationWriter writer,
            UsernameLookup usernames, NotificationPushes pushes) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.writer = writer;
        this.usernames = usernames;
        this.pushes = pushes;
    }

    public void followed(FollowEvent event) {
        String username = usernames.username(event.followerSub());
        Notification notification = NotificationFactory.follow(event.followedSub(), event.followerSub(),
                username == null ? Map.of() : Map.of("actorUsername", username));
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

    public void unfollowed(FollowEvent event) {
        long deleted = transactions.execute(() -> {
            if (!applyState(event, false)) {
                return 0L;
            }
            Query target = Query.query(Criteria.where("recipientSub").is(event.followedSub())
                    .and("type").is(NotificationType.FOLLOW).and("follow.followerSub").is(event.followerSub()));
            return mongo.remove(target, Notification.class).getDeletedCount();
        });
        if (deleted > 0) {
            pushes.unreadChanged(event.followedSub());
        }
    }

    private boolean applyState(FollowEvent event, boolean active) {
        String id = event.followerSub() + "|" + event.followedSub();
        FollowState current = mongo.findById(id, FollowState.class);
        State ordering = current == null ? null : new State(current.getLastEventId(), current.getLastEventTime(),
                current.getLastRelationshipSequence(), current.isActive());
        Decision decision = RelationshipOrdering.decide(ordering, event.eventId(), event.occurredAt(),
                event.relationshipSequence(), active);
        if (decision == Decision.CONFLICT) {
            throw new AmqpRejectAndDontRequeueException("Conflicting follow sequence");
        }
        if (decision != Decision.APPLY) {
            return false;
        }
        // Keep the producer sequence high-water mark if a newer legacy payload arrives.
        Long sequence = event.relationshipSequence() != null ? event.relationshipSequence()
                : current == null ? null : current.getLastRelationshipSequence();
        mongo.save(new FollowState(id, event.followerSub(), event.followedSub(), active, event.occurredAt(),
                event.occurredAt().toString(), event.eventId(), sequence));
        return true;
    }
}
