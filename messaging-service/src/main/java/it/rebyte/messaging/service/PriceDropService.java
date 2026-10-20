package it.rebyte.messaging.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.FavouriteProjection;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.messaging.ListingPriceEvent;

@Service
public class PriceDropService {

    private static final int PAGE_SIZE = 500;
    private final MongoTemplate mongo;
    private final NotificationWriter writer;
    private final NotificationPushes pushes;

    public PriceDropService(MongoTemplate mongo, NotificationWriter writer, NotificationPushes pushes) {
        this.mongo = mongo;
        this.writer = writer;
        this.pushes = pushes;
    }

    public int fanOut(ListingPriceEvent event) {
        if (event.oldPrice() == null || event.newPrice() == null
                || event.newPrice().compareTo(event.oldPrice()) >= 0) {
            return 0;
        }
        Instant instant = event.occurredAt().truncatedTo(ChronoUnit.MILLIS);
        Map<String, String> snapshot = new HashMap<>();
        snapshot.put("listingTitle", event.listingTitle());
        snapshot.put("oldPrice", event.oldPrice().toPlainString());
        snapshot.put("newPrice", event.newPrice().toPlainString());
        String cursor = null;
        int insertedCount = 0;
        while (true) {
            Criteria criteria = Criteria.where("listingId").is(event.listingId()).and("active").is(true);
            if (cursor != null) {
                criteria.and("userSub").gt(cursor);
            }
            List<FavouriteProjection> page = mongo.find(Query.query(criteria)
                    .with(Sort.by(Sort.Direction.ASC, "userSub")).limit(PAGE_SIZE), FavouriteProjection.class);
            if (page.isEmpty()) {
                return insertedCount;
            }
            List<Notification> notifications = page.stream().map(favourite -> NotificationFactory.priceDrop(
                    favourite.getUserSub(), event.listingId(), instant, snapshot)).toList();
            // A duplicate in a transaction would abort the entire page, so fan-outs stay non-transactional.
            List<Notification> inserted = writer.insertAllIgnoringDuplicates(notifications);
            pushes.created(inserted);
            insertedCount += inserted.size();
            if (page.size() < PAGE_SIZE) {
                return insertedCount;
            }
            cursor = page.getLast().getUserSub();
        }
    }
}
