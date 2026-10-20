package it.rebyte.catalog.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Degrades {@code /actuator/health} once outbox lag passes a configurable
 * threshold, and reports up again once the backlog drains — mirrors
 * user-service's {@code OutboxLagHealthIndicator} exactly.
 */
@Component
public class OutboxLagHealthIndicator implements HealthIndicator {

    private final OutboxMetrics outboxMetrics;
    private final double thresholdSeconds;

    public OutboxLagHealthIndicator(
            OutboxMetrics outboxMetrics,
            @Value("${rebyte.outbox.relay.lag-threshold-seconds:60}") double thresholdSeconds) {
        this.outboxMetrics = outboxMetrics;
        this.thresholdSeconds = thresholdSeconds;
    }

    @Override
    public Health health() {
        double lagSeconds = outboxMetrics.lagSeconds();
        if (lagSeconds > thresholdSeconds) {
            return Health.down()
                    .withDetail("lagSeconds", lagSeconds)
                    .withDetail("thresholdSeconds", thresholdSeconds)
                    .build();
        }
        return Health.up().withDetail("lagSeconds", lagSeconds).build();
    }
}
