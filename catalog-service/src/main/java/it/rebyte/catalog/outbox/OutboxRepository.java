package it.rebyte.catalog.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Le query di conteggio/mark/purge che {@link OutboxRelay} e {@link OutboxPurgeJob}
 * eseguono su {@code outbox_event}. La claim vive a parte, in {@link
 * OutboxClaimRepository} — non e una derived query, e un
 * {@code findAndModify} atomico (02-RESEARCH.md Pattern 3).
 */
public interface OutboxRepository extends MongoRepository<OutboxEvent, String> {

    /** Backs the {@code rebyte.outbox.pending} gauge. */
    long countByPublishedAtIsNull();

    /** Backs the {@code rebyte.outbox.claimed} gauge — il segnale che distingue una claim bloccata da un backlog genuino. */
    long countByClaimedAtIsNotNullAndPublishedAtIsNull();

    /** Backs the {@code rebyte.outbox.lag.seconds} gauge; vuoto quando non c'e nulla in sospeso. */
    Optional<OutboxEvent> findFirstByPublishedAtIsNullOrderByOccurredAtAsc();

    /** D-19-equivalent: solo il purge job rimuove righe, e solo oltre la finestra di retention. */
    long deleteByPublishedAtNotNullAndPublishedAtBefore(Instant cutoff);

    /** Events that may have raced an index rebuild snapshot. */
    List<OutboxEvent> findByOccurredAtGreaterThanEqualOrderByOccurredAtAsc(Instant rebuildStartedAt);
}
