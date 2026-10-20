package it.rebyte.messaging.service;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.dto.UnreadSummary;

/**
 * Unread totals of one user: every unread notification, whatever its type, and
 * the unread counters of the conversations the user takes part in as buyer or
 * seller.
 */
@Service
public class UnreadSummaryService {

    private final MongoTemplate mongoTemplate;

    public UnreadSummaryService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public UnreadSummary summary(String sub) {
        long notifications = mongoTemplate.count(
                Query.query(Criteria.where("recipientSub").is(sub).and("read").is(false)), Notification.class);
        long messages = unreadAs("buyerSub", "buyerUnread", sub) + unreadAs("sellerSub", "sellerUnread", sub);
        return new UnreadSummary(notifications, messages);
    }

    private long unreadAs(String participantField, String counterField, String sub) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where(participantField).is(sub)),
                Aggregation.group().sum(counterField).as("total"));
        Document result = mongoTemplate.aggregate(aggregation, Conversation.class, Document.class)
                .getUniqueMappedResult();
        return result == null ? 0 : ((Number) result.get("total")).longValue();
    }
}
