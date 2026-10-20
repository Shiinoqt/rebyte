package it.rebyte.messaging.outbox;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Count, mark and purge queries over {@code outbox_event}. The claim lives in
 * {@link OutboxClaimRepository} because it is an atomic {@code findAndModify},
 * not a derived query.
 */
public interface OutboxRepository extends MongoRepository<OutboxEvent, String> {

    /** Backs the {@code rebyte.outbox.pending} gauge. */
    long countByPublishedAtIsNull();

    /** Backs the {@code rebyte.outbox.claimed} gauge. */
    long countByClaimedAtIsNotNullAndPublishedAtIsNull();

    /** Backs the {@code rebyte.outbox.lag.seconds} gauge; empty when nothing is pending. */
    Optional<OutboxEvent> findFirstByPublishedAtIsNullOrderByOccurredAtAsc();

    /** Never matches unpublished rows: a range comparison does not match a null publishedAt. */
    long deleteByPublishedAtBefore(Instant cutoff);
}
