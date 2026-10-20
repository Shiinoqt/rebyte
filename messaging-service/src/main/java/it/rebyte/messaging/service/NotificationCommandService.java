package it.rebyte.messaging.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.dto.NotificationView;
import it.rebyte.messaging.dto.ReadAllResult;
import it.rebyte.messaging.error.InvalidDeleteFilterException;
import it.rebyte.messaging.error.NotificationNotFoundException;
import it.rebyte.messaging.error.NotificationUnreadException;

@Service
public class NotificationCommandService {

    private final MongoTemplate mongo;
    private final NotificationPushes pushes;
    private final Duration retention;

    public NotificationCommandService(MongoTemplate mongo, NotificationPushes pushes,
            @Value("${rebyte.notifications.read-retention}") Duration retention) {
        this.mongo = mongo;
        this.pushes = pushes;
        this.retention = retention;
    }

    public NotificationView markRead(String callerSub, String id) {
        Notification existing = required(callerSub, id);
        if (existing.isRead()) {
            return NotificationView.from(existing);
        }
        Query unread = Query.query(owned(callerSub, id).and("read").is(false));
        Notification changed = mongo.findAndModify(unread, readUpdate(),
                FindAndModifyOptions.options().returnNew(true), Notification.class);
        if (changed == null) {
            // Another reader may have won the conditional update; return its first read timestamp.
            return NotificationView.from(required(callerSub, id));
        }
        pushes.unreadChanged(callerSub);
        return NotificationView.from(changed);
    }

    public ReadAllResult markAllRead(String callerSub) {
        Query unread = Query.query(Criteria.where("recipientSub").is(callerSub).and("read").is(false));
        long updated = mongo.updateMulti(unread, readUpdate(), Notification.class).getModifiedCount();
        pushes.unreadChanged(callerSub);
        return new ReadAllResult(updated);
    }

    public void delete(String callerSub, String id) {
        Notification existing = required(callerSub, id);
        if (!existing.isRead()) {
            throw new NotificationUnreadException();
        }
        mongo.remove(Query.query(owned(callerSub, id).and("read").is(true)), Notification.class);
    }

    public void deleteRead(String callerSub, Boolean read) {
        if (!Boolean.TRUE.equals(read)) {
            throw new InvalidDeleteFilterException();
        }
        mongo.remove(Query.query(Criteria.where("recipientSub").is(callerSub).and("read").is(true)),
                Notification.class);
    }

    private Notification required(String callerSub, String id) {
        Notification notification = mongo.findOne(Query.query(owned(callerSub, id)), Notification.class);
        if (notification == null) {
            throw new NotificationNotFoundException();
        }
        return notification;
    }

    private Criteria owned(String callerSub, String id) {
        return Criteria.where("recipientSub").is(callerSub).and("_id").is(id);
    }

    private Update readUpdate() {
        Instant readAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Update().set("read", true).set("readAt", readAt).set("expiresAt", readAt.plus(retention));
    }
}
