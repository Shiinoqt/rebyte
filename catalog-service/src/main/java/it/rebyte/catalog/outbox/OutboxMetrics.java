package it.rebyte.catalog.outbox;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Three Micrometer gauges — the same two user-service registers under the
 * SAME names (one dashboard covers both services), plus a third with no
 * counterpart there: {@code rebyte.outbox.claimed}, the count of documents
 * currently claimed but unpublished. Without it, the Mongo claim's one
 * genuinely new failure mode — a relay crashing between claim and mark,
 * leaving a document stranded until the lease expires ({@link
 * OutboxReaper}) — is invisible: {@code rebyte.outbox.pending} alone cannot
 * distinguish "backlogged" from "quietly stuck."
 */
@Component
public class OutboxMetrics {

    private final OutboxRepository outboxRepository;

    public OutboxMetrics(MeterRegistry registry, OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
        Gauge.builder("rebyte.outbox.pending", outboxRepository, OutboxRepository::countByPublishedAtIsNull)
                .description("Count of outbox rows not yet published")
                .register(registry);
        Gauge.builder("rebyte.outbox.lag.seconds", this, OutboxMetrics::lagSeconds)
                .description("Age in seconds of the oldest unpublished outbox row")
                .register(registry);
        Gauge.builder("rebyte.outbox.claimed", outboxRepository, OutboxRepository::countByClaimedAtIsNotNullAndPublishedAtIsNull)
                .description("Count of outbox rows currently claimed but not yet published — "
                        + "the signal that distinguishes a stuck claim from a genuine backlog")
                .register(registry);
    }

    /** Also used directly by {@link OutboxLagHealthIndicator}. */
    double lagSeconds() {
        return outboxRepository.findFirstByPublishedAtIsNullOrderByOccurredAtAsc()
                .map(event -> Duration.between(event.getOccurredAt(), Instant.now()).toMillis() / 1000.0)
                .orElse(0d);
    }
}
