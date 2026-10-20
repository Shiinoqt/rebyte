package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;

import it.rebyte.user.messaging.UserFollowedEvent;
import it.rebyte.user.outbox.DomainEventRecorder;
import it.rebyte.user.outbox.OutboxEvent;
import it.rebyte.user.outbox.OutboxLagHealthIndicator;
import it.rebyte.user.outbox.OutboxMetrics;
import it.rebyte.user.outbox.OutboxPurgeJob;
import it.rebyte.user.outbox.OutboxRelay;
import it.rebyte.user.outbox.OutboxRepository;
import it.rebyte.user.support.RabbitTestBase;

/**
 * Proves the outbox relay's resilience properties against a real MySQL and
 * RabbitMQ (D-14, D-16, D-17, D-18, D-19, D-20) without waiting on real
 * {@code @Scheduled} tick timing — {@code fixed-delay-ms}/{@code cron} are
 * pushed far out via {@code @TestPropertySource} so the background
 * scheduler cannot fire mid-assertion; every drain in this class is driven
 * explicitly.
 *
 * <p>The confirm-failure and exception-swallowing cases construct their
 * own throwaway {@link OutboxRelay} with a Mockito-mocked
 * {@link RabbitTemplate}, bypassing the Spring-managed bean for just that
 * one call — deterministic proof of the DB-side reaction to a broker
 * signal, rather than racing a real network timeout. Every other assertion
 * in this class runs the real, Spring-managed relay against the real
 * Testcontainers broker.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = {
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *"
})
class OutboxRelayResilienceIT extends RabbitTestBase {

    @Autowired
    private DomainEventRecorder domainEventRecorder;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Autowired
    private OutboxPurgeJob purgeJob;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * Every test writes and drains its own rows; without this, a row left
     * unpublished by one test (e.g. an assertion failure before its own
     * drain runs) inflates every later test's counts, since this class
     * shares one Spring context and one database across all its
     * {@code @Test} methods (no per-method transaction rollback).
     */
    @AfterEach
    void cleanUpOutbox() {
        outboxRepository.deleteAll();
    }

    @Test
    void domainWriteAndOutboxRowShareOneTransactionRollingBackTogether() {
        new TransactionTemplate(transactionManager).execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                recordFollowedEvent("evt-rollback-test");
                status.setRollbackOnly();
            }
        });

        assertThat(outboxRepository.findAll())
                .noneMatch(row -> "evt-rollback-test".equals(row.getEventId()));
    }

    @Test
    void burstOfSeveralHundredRowsDrainsWithinOneSchedulerTick() {
        writeRows(250, "burst");

        outboxRelay.drain();

        assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();
    }

    @Test
    void confirmFailureLeavesTheWholeBatchUnpublishedForTheNextTick() {
        writeRows(5, "confirm-failure");

        RabbitTemplate failingTemplate = mock(RabbitTemplate.class);
        when(failingTemplate.invoke(any())).thenReturn(Boolean.FALSE);
        OutboxRelay relayWithFailingConfirm =
                new OutboxRelay(outboxRepository, failingTemplate, transactionManager, 100, 50, 5000);

        relayWithFailingConfirm.publishNextBatch();

        assertThat(outboxRepository.countByPublishedAtIsNull()).isEqualTo(5);

        // The next tick, against the real broker, republishes the same rows.
        outboxRelay.drain();
        assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();
    }

    @Test
    void exceptionInsideATickIsCaughtAndTheSchedulerKeepsFiring() {
        writeRows(3, "exception-tick");

        RabbitTemplate throwingTemplate = mock(RabbitTemplate.class);
        when(throwingTemplate.invoke(any())).thenThrow(new RuntimeException("simulated broker failure"));
        OutboxRelay relayThatThrows =
                new OutboxRelay(outboxRepository, throwingTemplate, transactionManager, 100, 50, 5000);

        relayThatThrows.drain(); // must not propagate (D-16)

        assertThat(outboxRepository.countByPublishedAtIsNull()).isEqualTo(3);

        // The following tick, on the real bean, still runs.
        outboxRelay.drain();
        assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();
    }

    @Test
    void lagAndPendingGaugesReadZeroWhenDrainedAndPositiveWhenBacklogged() {
        writeRows(2, "gauges");

        double pendingBefore = meterRegistry.get("rebyte.outbox.pending").gauge().value();
        double lagBefore = meterRegistry.get("rebyte.outbox.lag.seconds").gauge().value();
        assertThat(pendingBefore).isGreaterThanOrEqualTo(2.0);
        assertThat(lagBefore).isGreaterThanOrEqualTo(0.0);

        outboxRelay.drain();

        assertThat(meterRegistry.get("rebyte.outbox.pending").gauge().value()).isZero();
        assertThat(meterRegistry.get("rebyte.outbox.lag.seconds").gauge().value()).isZero();
    }

    @Test
    void healthDegradesPastThresholdAndRecoversAfterDrain() {
        writeRows(1, "health-degrade");

        // A strict, hand-constructed indicator (threshold 0.0) makes even a
        // just-written row's tiny positive lag exceed the threshold —
        // deterministic, no need to wait out the real 60s default.
        OutboxLagHealthIndicator strictIndicator = new OutboxLagHealthIndicator(outboxMetrics, 0.0);
        assertThat(strictIndicator.health().getStatus()).isEqualTo(Status.DOWN);

        outboxRelay.drain();

        assertThat(strictIndicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void purgeJobRemovesOnlyRowsPastRetentionWindow() {
        Instant tenDaysAgo = Instant.now().minus(Duration.ofDays(10)).truncatedTo(ChronoUnit.MICROS);
        Instant oneHourAgo = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);

        OutboxEvent oldRow = new OutboxEvent("purge-old", "user.followed", "{}", tenDaysAgo);
        oldRow.markPublished(tenDaysAgo);
        OutboxEvent recentRow = new OutboxEvent("purge-recent", "user.followed", "{}", oneHourAgo);
        recentRow.markPublished(oneHourAgo);
        outboxRepository.save(oldRow);
        outboxRepository.save(recentRow);

        purgeJob.purge();

        List<OutboxEvent> remaining = outboxRepository.findAll();
        assertThat(remaining).noneMatch(row -> "purge-old".equals(row.getEventId()));
        assertThat(remaining).anyMatch(row -> "purge-recent".equals(row.getEventId()));
    }

    private void writeRows(int count, String prefix) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                recordFollowedEvent(prefix + "-" + i);
            }
        });
    }

    private void recordFollowedEvent(String eventId) {
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        domainEventRecorder.record(eventId, "user.followed",
                new UserFollowedEvent(eventId, "follower-" + eventId, "followed-" + eventId, occurredAt),
                occurredAt);
    }
}
