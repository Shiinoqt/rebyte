package it.rebyte.user.outbox;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Claims, publishes and marks outbox rows (D-14, D-16, D-18, D-20).
 *
 * <p><b>Claim-publish-confirm-mark, one transaction.</b>
 * {@link #publishNextBatch()} claims a batch with
 * {@link OutboxRepository#claimBatch} (skip-locked, D-14), publishes the
 * batch and waits once for the broker's publisher confirm (D-18), then
 * marks the batch published only on a clean confirm — all inside the same
 * transaction, so the row locks held by the claim cover the whole publish.
 * A crashed instance releases its claimed rows when its connection drops;
 * there is deliberately no stale-claim reaper.
 *
 * <p><b>Programmatic transaction, not {@code @Transactional} (deviation
 * found during this plan's own resilience test).</b>
 * {@link #publishNextBatch()} is called from {@link #drainUntilEmptyOrCapped()}
 * on {@code this} — a same-class ("self-invocation") call. Spring's
 * declarative {@code @Transactional} works by wrapping the bean in an AOP
 * proxy; a self-invocation never goes back out through that proxy, so an
 * {@code @Transactional} annotation on a method only ever reached via
 * self-invocation silently does nothing — the skip-locked claim query then
 * fails at runtime with {@code TransactionRequiredException: No active
 * transaction}. This is a well-documented Spring AOP pitfall, hit for real
 * running {@code OutboxRelayResilienceIT} against the genuine
 * Spring-managed bean, not merely a theoretical risk. Using an explicit
 * {@link TransactionTemplate} makes the transaction boundary proxy-independent
 * — it works identically whether {@link #drain()} calls {@code this} or a
 * test constructs {@link OutboxRelay} directly (bypassing Spring DI
 * entirely) to inject a mocked {@link RabbitTemplate}.
 *
 * <p><b>Drain loop, capped per tick (D-20).</b> {@link #drain()} is the
 * {@code @Scheduled} entry point, firing every second by
 * {@code fixedDelay} (not {@code fixedRate} — fixedDelay measures from the
 * previous execution's completion, so two ticks can never overlap and race
 * each other's transaction). Each tick loops calling
 * {@link #publishNextBatch()} until either the table has nothing left
 * unpublished, a batch fails to confirm, or {@code maxBatchesPerTick} is
 * reached — so a burst of several hundred rows drains within one tick
 * instead of one small batch per second.
 *
 * <p><b>Silent death (D-16).</b> Every exception thrown inside
 * {@link #drain()} is caught and logged here, never allowed to escape the
 * {@code @Scheduled} method — an exception escaping a scheduled method
 * stops Spring's scheduler from re-triggering it, with no error afterwards
 * to notice (PITFALLS.md §6).
 *
 * <p><b>At-least-once (D-15).</b> A confirm that times out or returns
 * unclean leaves every row in that batch unpublished; the next tick claims
 * and republishes the whole batch. No claim mechanism buys exactly-once
 * end to end — only exactly-once under concurrent relays (D-23), which is
 * what {@code OutboxRelayExactlyOnceIT} proves. Every downstream consumer
 * must therefore be idempotent.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxBatchesPerTick;
    private final long confirmTimeoutMillis;

    public OutboxRelay(
            OutboxRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${rebyte.outbox.relay.batch-size:100}") int batchSize,
            @Value("${rebyte.outbox.relay.max-batches-per-tick:50}") int maxBatchesPerTick,
            @Value("${rebyte.outbox.relay.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.maxBatchesPerTick = maxBatchesPerTick;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /** The {@code @Scheduled} entry point — never lets an exception escape (D-16). */
    @Scheduled(fixedDelayString = "${rebyte.outbox.relay.fixed-delay-ms:1000}")
    public void drain() {
        try {
            drainUntilEmptyOrCapped();
        } catch (Exception e) {
            log.error("outbox relay tick failed; scheduler keeps firing on the next tick", e);
        }
    }

    /**
     * Drains every unpublished row, capped at {@code maxBatchesPerTick}
     * batches so one call cannot run unbounded. Used both by the scheduled
     * tick and directly by tests that need a full drain without waiting on
     * tick timing.
     */
    public void drainUntilEmptyOrCapped() {
        int batches = 0;
        BatchOutcome outcome;
        do {
            outcome = publishNextBatch();
            batches++;
        } while (outcome == BatchOutcome.CONFIRMED_MORE_LIKELY && batches < maxBatchesPerTick);
    }

    /**
     * Claims, publishes and marks one batch inside a single, programmatically
     * managed transaction (D-14, D-18 — see class Javadoc for why this is
     * {@link TransactionTemplate}, not {@code @Transactional}). Public so
     * tests can drive individual batches and inspect the outcome directly.
     */
    public BatchOutcome publishNextBatch() {
        return transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = outboxRepository.claimBatch(PageRequest.of(0, batchSize));
            if (batch.isEmpty()) {
                return BatchOutcome.EMPTY;
            }
            log.debug("claimed batch of {} rows on thread {}", batch.size(), Thread.currentThread().getName());

            Boolean confirmed = rabbitTemplate.invoke(template -> {
                for (OutboxEvent event : batch) {
                    template.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, event.getRoutingKey(), event.getPayload());
                }
                return template.waitForConfirms(confirmTimeoutMillis);
            });

            if (!Boolean.TRUE.equals(confirmed)) {
                // D-18: a partial/unclean confirm leaves the whole batch
                // unpublished. Nothing to undo — markPublished was never
                // called, so the transaction commits with no changes and the
                // next tick's claim sees these rows again.
                return BatchOutcome.CONFIRM_FAILED;
            }

            Instant publishedAt = Instant.now();
            batch.forEach(event -> event.markPublished(publishedAt));
            return batch.size() == batchSize ? BatchOutcome.CONFIRMED_MORE_LIKELY : BatchOutcome.CONFIRMED_DRAINED;
        });
    }

    /** Outcome of one claimed batch, used to decide whether the drain loop continues. */
    public enum BatchOutcome {
        /** Nothing was unpublished — nothing to claim. */
        EMPTY,
        /** Batch confirmed and marked published; the claim was full, so more rows may remain. */
        CONFIRMED_MORE_LIKELY,
        /** Batch confirmed and marked published; the claim was partial, so the table is drained. */
        CONFIRMED_DRAINED,
        /** The broker did not cleanly confirm; the batch stays unpublished for the next tick. */
        CONFIRM_FAILED
    }
}
