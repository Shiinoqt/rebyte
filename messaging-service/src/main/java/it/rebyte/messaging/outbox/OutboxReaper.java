package it.rebyte.messaging.outbox;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clears {@code claimedAt} on unpublished rows whose claim is older than
 * {@code rebyte.outbox.relay.lease-seconds}. A relay that dies between claiming
 * a batch and marking it published would otherwise leave those rows claimed
 * forever. The default delay is longer than the lease, so a live relay's
 * in-flight batch is not taken from it.
 */
@Component
public class OutboxReaper {

    private static final Logger log = LoggerFactory.getLogger(OutboxReaper.class);

    private final MongoTemplate mongoTemplate;
    private final Duration leaseWindow;

    public OutboxReaper(
            MongoTemplate mongoTemplate,
            @Value("${rebyte.outbox.relay.lease-seconds:60}") long leaseSeconds) {
        this.mongoTemplate = mongoTemplate;
        this.leaseWindow = Duration.ofSeconds(leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${rebyte.outbox.reaper.fixed-delay-ms:120000}")
    public void reap() {
        try {
            Instant leaseExpiry = Instant.now().minus(leaseWindow);
            Query query = Query.query(Criteria.where("publishedAt").isNull()
                    .and("claimedAt").ne(null).lt(leaseExpiry));
            Update release = Update.update("claimedAt", null);

            long released = mongoTemplate.updateMulti(query, release, OutboxEvent.class).getModifiedCount();
            if (released > 0) {
                log.info("released {} stranded outbox claims older than {}", released, leaseExpiry);
            }
        } catch (Exception e) {
            log.error("outbox reaper sweep failed", e);
        }
    }
}
