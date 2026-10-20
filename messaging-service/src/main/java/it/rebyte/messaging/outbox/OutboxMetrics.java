package it.rebyte.messaging.outbox;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers the outbox gauges under the same names the other services use, so
 * one dashboard covers all of them. {@code rebyte.outbox.claimed} separates a
 * stuck claim from a plain backlog, which {@code rebyte.outbox.pending} alone
 * cannot do.
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
                .description("Count of outbox rows currently claimed but not yet published")
                .register(registry);
    }

    double lagSeconds() {
        return outboxRepository.findFirstByPublishedAtIsNullOrderByOccurredAtAsc()
                .map(event -> Duration.between(event.getOccurredAt(), Instant.now()).toMillis() / 1000.0)
                .orElse(0d);
    }
}
