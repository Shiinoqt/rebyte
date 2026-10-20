package it.rebyte.payment.outbox;

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

public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select o from OutboxEvent o where o.publishedAt is null order by o.occurredAt, o.id")
    List<OutboxEvent> claimBatch(Pageable page);

    long countByPublishedAtIsNull();

    @Query("select min(o.occurredAt) from OutboxEvent o where o.publishedAt is null")
    Instant oldestUnpublishedOccurredAt();

    @Modifying
    @Query("delete from OutboxEvent o where o.publishedAt is not null and o.publishedAt < :cutoff")
    long deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
