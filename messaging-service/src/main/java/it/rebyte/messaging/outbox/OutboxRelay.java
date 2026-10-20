package it.rebyte.messaging.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mongodb.MongoException;

import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Claims, publishes and marks outbox rows.
 *
 * <p>One batch is claimed, published with a single wait for the broker's
 * publisher confirm and marked published inside one {@code MongoTransactionManager}
 * transaction. A failed confirm sets rollback-only, which undoes the claim
 * writes too, so the batch is republishable on the next tick instead of waiting
 * for {@link OutboxReaper}. The transaction is programmatic because
 * {@link #publishNextBatch()} is reached by self-invocation, where a
 * declarative {@code @Transactional} would silently not apply.
 *
 * <p>Delivery is at least once: an unclean or timed-out confirm leaves the whole
 * batch unpublished and it is published again, so consumers must be idempotent.
 *
 * <p>Two relays claiming concurrently can make MongoDB abort one transaction
 * with a write conflict labelled {@code TransientTransactionError}. The whole
 * claim-publish-mark transaction is retried with a short random pause, since
 * nothing from an aborted attempt committed. Every exception is caught in
 * {@link #drain()} so the scheduler keeps firing.
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

    @Scheduled(fixedDelayString = "${rebyte.outbox.relay.fixed-delay-ms:1000}")
    public void drain() {
        try {
            drainUntilEmptyOrCapped();
        } catch (Exception e) {
            log.error("outbox relay tick failed; scheduler keeps firing on the next tick", e);
        }
    }

    /** Publishes batches until the outbox is empty, capped at {@code maxBatchesPerTick}. */
    public void drainUntilEmptyOrCapped() {
        int batches = 0;
        BatchOutcome outcome;
        do {
            outcome = publishNextBatch();
            batches++;
        } while (outcome == BatchOutcome.CONFIRMED_MORE_LIKELY && batches < maxBatchesPerTick);
    }

    /** Claims, publishes and marks one batch, retrying on a transient transaction error. */
    public BatchOutcome publishNextBatch() {
        RuntimeException lastTransientFailure = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    List<OutboxEvent> batch = claimBatch();
                    if (batch.isEmpty()) {
                        return BatchOutcome.EMPTY;
                    }

                    Boolean confirmed = rabbitTemplate.invoke(template -> {
                        for (OutboxEvent event : batch) {
                            template.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, event.getRoutingKey(), event.getPayload());
                        }
                        return template.waitForConfirms(confirmTimeoutMillis);
                    });

                    if (!Boolean.TRUE.equals(confirmed)) {
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

    /** Spreads retries so two colliding threads do not repeat the same claim in lockstep. */
    private static void backoffBeforeRetry() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, MAX_BACKOFF_MILLIS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Spring's exception translation wraps the driver exception, so the label
     * is only reachable through the cause chain.
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
        /** Nothing was unpublished. */
        EMPTY,
        /** Confirmed and marked published; the claim was full, so more rows may remain. */
        CONFIRMED_MORE_LIKELY,
        /** Confirmed and marked published; the claim was partial, so the outbox is drained. */
        CONFIRMED_DRAINED,
        /** The broker did not cleanly confirm; the transaction rolled back and the batch stays unclaimed. */
        CONFIRM_FAILED
    }
}
