package it.rebyte.catalog.outbox;

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
 * The component that has no counterpart in user-service's outbox, and the
 * reason it exists must be in this Javadoc rather than implied
 * (02-RESEARCH.md Pitfall 3). MySQL's {@code SKIP LOCKED} claim is a
 * transaction-scoped row lock: a relay that crashes mid-batch releases its
 * claim automatically when its database connection drops, so user-service's
 * relay needs no reaper. MongoDB's {@code findAndModify} claim ({@link
 * OutboxClaimRepository}) is a <b>committed document write</b>, not a
 * session-held lock — a relay that dies between claiming a batch and
 * marking it published leaves {@code claimedAt} set on those documents
 * forever, with nothing to release it, unless something else clears it.
 *
 * <p>This scheduled sweep is that something else: on its own thread (see
 * {@link it.rebyte.catalog.config.SchedulingConfig}) and with every
 * exception caught inside the scheduled method (same discipline as {@link
 * OutboxRelay#drain()}), it clears {@code claimedAt} on documents that are
 * still unpublished and whose claim is older than {@code
 * rebyte.outbox.relay.lease-seconds}. Runs at a fixed delay comfortably
 * longer than the lease, so a healthy in-flight batch a live relay is
 * actively working is never stolen out from under it.
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
