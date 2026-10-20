package it.rebyte.catalog.outbox;

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
 * The claim mechanism that has no counterpart in user-service's MySQL outbox
 * (02-RESEARCH.md Pattern 3, Pitfall 3). MySQL's {@code SKIP LOCKED} ties a
 * claim's lifetime to a live database session — a crashed relay releases it
 * automatically. MongoDB has no query-language equivalent; {@link
 * MongoTemplate#findAndModify} gives single-document atomic claiming, but
 * the claim itself is a <b>committed write</b>, not a session-held lock, so
 * two relay instances calling {@link #claimNext()} concurrently always get
 * disjoint documents, but a crashed relay's claim survives until {@link
 * OutboxReaper} clears it past the lease window.
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
     * Atomically claims and returns the oldest unpublished event that is
     * either unclaimed or claimed past the lease window, or empty if none
     * qualify. Must be called inside the same {@code @Transactional} method
     * that publishes and marks the batch — the transaction is what lets a
     * confirm failure roll the claim back too, making the batch immediately
     * republishable rather than waiting a full lease window (see {@link
     * OutboxRelay#publishNextBatch()}).
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
