package it.rebyte.user.outbox;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Two Micrometer gauges satisfying D-17's "observable rather than inferred
 * from an empty log" requirement: {@code rebyte.outbox.pending} (count of
 * unpublished rows) and {@code rebyte.outbox.lag.seconds} (age in seconds
 * of the oldest unpublished row, zero when nothing is pending). Trend
 * ({@code lag.seconds}) and alarm ({@link OutboxLagHealthIndicator}) answer
 * different questions and both ride on Actuator, already present in every
 * module.
 */
@Component
public class OutboxMetrics {

    private final OutboxRepository outboxRepository;

    public OutboxMetrics(MeterRegistry registry, OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
        Gauge.builder("rebyte.outbox.pending", outboxRepository, OutboxRepository::countByPublishedAtIsNull)
                .description("Count of outbox rows not yet published (D-17)")
                .register(registry);
        Gauge.builder("rebyte.outbox.lag.seconds", this, OutboxMetrics::lagSeconds)
                .description("Age in seconds of the oldest unpublished outbox row (D-17)")
                .register(registry);
    }

    /** Also used directly by {@link OutboxLagHealthIndicator}. */
    double lagSeconds() {
        Instant oldest = outboxRepository.oldestUnpublishedOccurredAt();
        if (oldest == null) {
            return 0d;
        }
        return Duration.between(oldest, Instant.now()).toMillis() / 1000.0;
    }
}
