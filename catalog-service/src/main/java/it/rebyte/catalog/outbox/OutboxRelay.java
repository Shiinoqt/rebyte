package it.rebyte.catalog.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mongodb.MongoException;

import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Claims, publishes and marks outbox rows — the Mongo-flavoured relay
 * (02-RESEARCH.md Pattern 3). Copies user-service's drain-loop, scheduling
 * and silent-death discipline verbatim; the claim step itself does not (see
 * {@link OutboxClaimRepository}).
 *
 * <p><b>Claim-publish-confirm-mark, one transaction.</b> {@link
 * #publishNextBatch()} claims up to {@code batchSize} rows via {@link
 * OutboxClaimRepository#claimNext()}, publishes the batch and waits once
 * for the broker's publisher confirm, then marks the batch published only
 * on a clean confirm — all inside the same {@code MongoTransactionManager}-backed
 * transaction, so the claim writes and the publish outcome commit or roll
 * back together.
 *
 * <p><b>A failed confirm rolls the whole batch back (deviation from a
 * literal MySQL port).</b> user-service's claim is a row lock, not a
 * persisted flag — a failed confirm there just returns without marking
 * anything published, and the lock releases on commit either way. Here the
 * claim IS a persisted {@code claimedAt} write; returning without rolling
 * back would leave the batch claimed-but-unpublished until {@link
 * OutboxReaper} releases it a whole lease window later. Calling {@code
 * status.setRollbackOnly()} on a confirm failure undoes the claim in the
 * same transaction that set it, so the batch is immediately republishable
 * on the next tick — matching the "failed confirm leaves the batch
 * republishable" contract user-service documents, without silently
 * inheriting a multi-minute delay that only exists because of how this
 * datastore's claim primitive differs.
 *
 * <p><b>Programmatic transaction, not {@code @Transactional}.</b> {@link
 * #publishNextBatch()} is reached via self-invocation from {@link
 * #drainUntilEmptyOrCapped()}, which a declarative {@code @Transactional}
 * would silently no-op on (the same Spring AOP pitfall user-service's own
 * Javadoc records). An explicit {@link TransactionTemplate} is
 * proxy-independent.
 *
 * <p><b>Silent death.</b> Every exception thrown inside {@link #drain()} is
 * caught and logged, never allowed to escape the {@code @Scheduled} method.
 *
 * <p><b>At-least-once.</b> A confirm that times out or returns unclean
 * leaves every row in that batch unpublished; the next tick reclaims and
 * republishes the whole batch. Every downstream consumer must therefore be
 * idempotent — see {@link it.rebyte.catalog.search.ListingIndexer}'s
 * sequence guard.
 *
 * <p><b>Transient transaction errors are retried (Rule 1 — found via
 * genuine RED evidence, not assumed).</b> Two relay instances calling
 * {@link OutboxClaimRepository#claimNext()} concurrently, each inside its
 * own {@code MongoTransactionManager}-backed transaction, can have MongoDB
 * abort one of them outright with a {@code WriteConflict} (error 112,
 * labelled {@code TransientTransactionError}) rather than silently
 * serializing the two claims — MongoDB's transactions do not block on a
 * write-write conflict the way a single non-transactional {@code
 * findAndModify} does; the official driver contract is that the
 * application retries the whole transaction. Caught running {@code
 * OutboxLeaseClaimIT}'s two-relay concurrency test against a real MongoDB
 * container: the first attempt reproduced the abort deterministically, not
 * a flaky one-off. {@link #publishNextBatch()} therefore retries the
 * entire claim-publish-confirm-mark transaction (a fresh {@link
 * #claimBatch()} each attempt, since nothing from an aborted attempt
 * committed) up to {@value #MAX_TRANSIENT_RETRIES} times before giving up
 * and letting the exception surface to {@link #drain()}'s catch-all.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String TRANSIENT_TRANSACTION_ERROR_LABEL = "TransientTransactionError";
    private static final int MAX_TRANSIENT_RETRIES = 40;
    private static final int MAX_BACKOFF_MILLIS = 40;

    private final OutboxClaimRepository claimRepository;
    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxBatchesPerTick;
    private final long confirmTimeoutMillis;

    public OutboxRelay(
            OutboxClaimRepository claimRepository,
            OutboxRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${rebyte.outbox.relay.batch-size:100}") int batchSize,
            @Value("${rebyte.outbox.relay.max-batches-per-tick:50}") int maxBatchesPerTick,
            @Value("${rebyte.outbox.relay.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        this.claimRepository = claimRepository;
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.maxBatchesPerTick = maxBatchesPerTick;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    /** The {@code @Scheduled} entry point — never lets an exception escape. */
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
     * managed transaction. Public so tests can drive individual batches and
     * inspect the outcome directly. Retries the whole transaction on a
     * {@code TransientTransactionError} (see class Javadoc) — every other
     * exception propagates immediately.
     */
    public BatchOutcome publishNextBatch() {
        RuntimeException lastTransientFailure = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    List<OutboxEvent> batch = claimBatch();
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
                        // Roll the claim back too, so the batch is reclaimable on the
                        // very next tick instead of waiting a full lease window — see
                        // class Javadoc.
                        status.setRollbackOnly();
                        return BatchOutcome.CONFIRM_FAILED;
                    }

                    Instant publishedAt = Instant.now();
                    for (OutboxEvent event : batch) {
                        event.markPublished(publishedAt);
                        outboxRepository.save(event);
                    }
                    return batch.size() == batchSize ? BatchOutcome.CONFIRMED_MORE_LIKELY : BatchOutcome.CONFIRMED_DRAINED;
                });
            } catch (RuntimeException e) {
                if (!isTransientTransactionError(e)) {
                    throw e;
                }
                lastTransientFailure = e;
                log.debug("transient transaction error on batch attempt {}/{}, retrying", attempt, MAX_TRANSIENT_RETRIES, e);
                backoffBeforeRetry();
            }
        }
        throw lastTransientFailure;
    }

    /**
     * A small randomized pause between retries, so two threads that just
     * collided on the same candidate document do not immediately race the
     * identical claim query again in lockstep — observed running {@code
     * OutboxLeaseClaimIT}'s two-relay concurrency test: with zero backoff,
     * two threads scheduled close together kept re-colliding on the same
     * oldest-unclaimed row for several consecutive attempts.
     */
    private static void backoffBeforeRetry() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, MAX_BACKOFF_MILLIS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Walks the exception's cause chain for a {@link MongoException} carrying
     * the {@code TransientTransactionError} label — Spring's {@link
     * org.springframework.data.mongodb.core.MongoExceptionTranslator} wraps
     * the original driver exception into a generic {@link
     * DataAccessException}, so the label is only reachable via the cause
     * chain, not on the translated exception itself.
     */
    private static boolean isTransientTransactionError(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof MongoException mongoException
                    && mongoException.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<OutboxEvent> claimBatch() {
        List<OutboxEvent> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            Optional<OutboxEvent> claimed = claimRepository.claimNext();
            if (claimed.isEmpty()) {
                break;
            }
            batch.add(claimed.get());
        }
        return batch;
    }

    /** Outcome of one claimed batch, used to decide whether the drain loop continues. */
    public enum BatchOutcome {
        /** Nothing was unpublished — nothing to claim. */
        EMPTY,
        /** Batch confirmed and marked published; the claim was full, so more rows may remain. */
        CONFIRMED_MORE_LIKELY,
        /** Batch confirmed and marked published; the claim was partial, so the table is drained. */
        CONFIRMED_DRAINED,
        /** The broker did not cleanly confirm; the transaction rolled back, so the batch stays unclaimed for the next tick. */
        CONFIRM_FAILED
    }
}
