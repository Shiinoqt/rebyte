package it.rebyte.messaging.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports {@code /actuator/health} DOWN while outbox lag is above the
 * configured threshold and UP again once the backlog drains.
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
