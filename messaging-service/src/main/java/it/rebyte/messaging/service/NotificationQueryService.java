package it.rebyte.messaging.service;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.dto.NotificationPage;
import it.rebyte.messaging.dto.NotificationView;
import it.rebyte.messaging.dto.UnreadSummary;

@Service
public class NotificationQueryService {

    private final MongoTemplate mongo;
    private final UnreadSummaryService unreadSummary;

    public NotificationQueryService(MongoTemplate mongo, UnreadSummaryService unreadSummary) {
        this.mongo = mongo;
        this.unreadSummary = unreadSummary;
    }

    public NotificationPage list(String callerSub, Boolean unread, String cursor, Integer requestedSize) {
        int size = Cursors.pageSize(requestedSize);
        Cursors.InstantIdCursor before = Cursors.decodeInstantId(cursor);
        Criteria criteria = Criteria.where("recipientSub").is(callerSub);
        if (Boolean.TRUE.equals(unread)) {
            criteria.and("read").is(false);
        }
        if (before != null) {
            criteria.orOperator(Criteria.where("createdAt").lt(before.at()),
                    Criteria.where("createdAt").is(before.at()).and("_id").lt(before.id()));
        }
        Query query = Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "createdAt", "_id"))
                .limit(size + 1);
        List<Notification> rows = mongo.find(query, Notification.class);
        List<Notification> page = rows.subList(0, Math.min(rows.size(), size));
        String nextCursor = null;
        if (rows.size() > size) {
            Notification last = page.get(page.size() - 1);
            nextCursor = Cursors.encodeInstantId(last.getCreatedAt(), last.getId());
        }
        return new NotificationPage(page.stream().map(NotificationView::from).toList(), nextCursor);
    }

    public UnreadSummary summary(String callerSub) {
        return unreadSummary.summary(callerSub);
    }
}
