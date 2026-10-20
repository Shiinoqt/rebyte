package it.rebyte.messaging.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Claims outbox rows with a single-document atomic {@code findAndModify}, so
 * concurrent relays always receive disjoint rows. The claim is a committed
 * write rather than a held lock: a crashed relay's claim survives until
 * {@link OutboxReaper} clears it past the lease window.
 */
@Repository
public class OutboxClaimRepository {

    private final MongoTemplate mongoTemplate;
    private final Duration leaseWindow;

    public OutboxClaimRepository(
            MongoTemplate mongoTemplate,
            @Value("${rebyte.outbox.relay.lease-seconds:60}") long leaseSeconds) {
        this.mongoTemplate = mongoTemplate;
        this.leaseWindow = Duration.ofSeconds(leaseSeconds);
    }

    /**
     * Claims and returns the oldest unpublished row that is unclaimed or whose
     * claim is past the lease window. Call it inside the transaction that
     * publishes and marks the batch, so a failed confirm rolls the claim back
     * and the batch is republishable on the next tick.
     */
    public Optional<OutboxEvent> claimNext() {
        Instant leaseExpiry = Instant.now().minus(leaseWindow);
        Criteria unclaimedOrExpired = new Criteria().orOperator(
                Criteria.where("claimedAt").isNull(),
                Criteria.where("claimedAt").lt(leaseExpiry));
        Query query = Query.query(Criteria.where("publishedAt").isNull().andOperator(unclaimedOrExpired))
                .with(Sort.by(Sort.Direction.ASC, "occurredAt"));
        Update claim = Update.update("claimedAt", Instant.now());

        OutboxEvent claimed = mongoTemplate.findAndModify(
                query, claim, FindAndModifyOptions.options().returnNew(true), OutboxEvent.class);
        return Optional.ofNullable(claimed);
    }
}
