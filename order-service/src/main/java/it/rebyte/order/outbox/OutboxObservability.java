package it.rebyte.order.outbox;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component("orderOutbox")
public class OutboxObservability implements HealthIndicator {

    private final OutboxRepository repository;

    public OutboxObservability(OutboxRepository repository, MeterRegistry registry) {
        this.repository = repository;
        Gauge.builder("rebyte.order.outbox.pending", repository, OutboxRepository::countByPublishedAtIsNull)
                .register(registry);
        Gauge.builder("rebyte.order.outbox.lag.seconds", this, OutboxObservability::lagSeconds)
                .register(registry);
    }

    public double lagSeconds() {
        Instant oldest = repository.oldestUnpublishedOccurredAt();
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds());
    }

    @Override
    public Health health() {
        long pending = repository.countByPublishedAtIsNull();
        double lag = lagSeconds();
        Health.Builder result = lag > 300 ? Health.down() : Health.up();
        return result.withDetail("pending", pending).withDetail("lagSeconds", lag).build();
    }
}
