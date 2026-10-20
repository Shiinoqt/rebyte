package it.rebyte.order.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {

    // An ordered index scan avoids locking the entire pending backlog before applying the batch limit.
    @Query(value = "select id, event_type, aggregate_id, payload, occurred_at, published_at "
            + "from order_outbox_events force index (idx_order_outbox_unpublished) "
            + "where published_at is null order by occurred_at, id for update skip locked", nativeQuery = true)
    List<OutboxEvent> claimBatch(Pageable page);

    long countByPublishedAtIsNull();

    @Query("select min(o.occurredAt) from OutboxEvent o where o.publishedAt is null")
    Instant oldestUnpublishedOccurredAt();

    @Modifying
    @Query("delete from OutboxEvent o where o.publishedAt is not null and o.publishedAt < :cutoff")
    long deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
