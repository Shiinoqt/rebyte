package it.rebyte.user.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Degrades {@code /actuator/health} once outbox lag passes a configurable
 * threshold, and reports up again once the backlog drains (D-17).
 *
 * <p>Boot 4.x deviation: {@code HealthIndicator}/{@code Health} moved from
 * {@code org.springframework.boot.actuate.health} to
 * {@code org.springframework.boot.health.contributor} in the new
 * {@code spring-boot-health} module (verified against Boot 4.1.0 source) —
 * the pre-4.x package no longer exists.
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
