package it.rebyte.user.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

/**
 * The claim/mark/purge queries {@link OutboxRelay} and {@link OutboxPurgeJob}
 * run against {@code outbox_event}.
 *
 * <p>{@link #claimBatch(Pageable)} is D-14's skip-locked claim:
 * {@code jakarta.persistence.LockModeType} has no {@code SKIP_LOCKED} value,
 * so the behaviour is requested via the {@code jakarta.persistence.lock.timeout}
 * hint set to Hibernate's {@code -2} magic value, which MySQL 8.4's dialect
 * (confirmed supported — 01-RESEARCH.md P2.1) renders as
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}. Must be called inside the same
 * {@code @Transactional} method that publishes and marks the batch (D-14) —
 * the transaction boundary is what releases the row locks on commit or
 * rollback, so a crashed relay instance needs no separate reaper.
 */
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select o from OutboxEvent o where o.publishedAt is null order by o.id asc")
    List<OutboxEvent> claimBatch(Pageable pageable);

    /** Backs the {@code rebyte.outbox.pending} gauge (D-17). */
    long countByPublishedAtIsNull();

    /** Backs the {@code rebyte.outbox.lag.seconds} gauge (D-17); {@code null} when nothing is pending. */
    @Query("select min(o.occurredAt) from OutboxEvent o where o.publishedAt is null")
    Instant oldestUnpublishedOccurredAt();

    /** D-19: only the purge job removes rows, and only once past the retention window. */
    @Modifying
    @Query("delete from OutboxEvent o where o.publishedAt is not null and o.publishedAt < :cutoff")
    long deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
