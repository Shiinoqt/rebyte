package it.rebyte.payment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.payment.client.OrderCaptureAuthorizationClient;
import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.domain.PaymentState;
import it.rebyte.payment.error.CaptureDeniedException;
import it.rebyte.payment.error.PaymentClosedException;
import it.rebyte.payment.error.PayPalProviderException;
import it.rebyte.payment.outbox.DomainEventRecorder;
import it.rebyte.payment.provider.PayPalClient;
import it.rebyte.payment.provider.PayPalDtos.OrderResponse;
import it.rebyte.payment.repository.PaymentRepository;

/**
 * The reconciliation sweep (03-10-PLAN.md Task 1; D-24, D-25, D-26, D-29).
 * Runs about once a minute over Payment rows past {@code
 * rebyte.checkout.payment-timeout} that no verified webhook has yet
 * resolved ({@link PaymentRepository#findDueForReconciliation}).
 *
 * <p><b>Per-row decision (single GET, at most):</b>
 * <ul>
 *   <li>{@code CAPTURING} with a provider capture id already recorded — the
 *       outcome is already known from the capture-time response itself; no
 *       PayPal call is made, only {@link #flagCapturedAwaitingWebhook}
 *       (D-26/D-29).</li>
 *   <li>Otherwise GET the PayPal order. {@code APPROVED} reuses {@link
 *       PaymentService#capture(String, String)} verbatim — the same
 *       claim/grant/dispatch guards a buyer-triggered capture uses, so a
 *       crash after minting the operation id but before dispatch ("lost
 *       grant") is recovered for free by simply retrying that same
 *       idempotent path. {@code COMPLETED} flags captured-awaiting-webhook.
 *       Anything else, for a never-claimed ({@code OPEN}) payment, means
 *       the order was never approved before the timeout — {@link
 *       #expireUnclaimed} closes it and emits one {@code payment.expired}.
 *       The identical "anything else" outcome for an already-claimed
 *       ({@code CAPTURING}) payment is explicitly NOT terminal proof and is
 *       left untouched (retains the guard for the next cycle).</li>
 *   <li>A qualified GET failure — HTTP 404, provider error name {@code
 *       RESOURCE_NOT_FOUND}, {@code details[].issue} containing {@code
 *       INVALID_RESOURCE_ID} (03-SANDBOX-SPIKE.md's GET-AFTER-EXPIRY row;
 *       validated against PayPal's own "Resource not found" troubleshooting
 *       doc during this task) — is the ONLY terminal no-charge proof this
 *       service accepts. It means the provider order resource itself no
 *       longer exists, so no capture request against that same resource id
 *       — including one already in flight — can ever succeed either,
 *       whether it began before or during this sweep's own GET. A CAPTURING
 *       payment is then fenced to {@code TERMINAL_NO_CHARGE} and one
 *       correlated {@code payment.expired} event is emitted — but ONLY if
 *       {@link PaymentRepository#fenceTerminalNoCharge} actually claims the
 *       transition (still {@code CAPTURING}); if a concurrent capture
 *       already recorded a provider capture id, or the row already moved
 *       away from {@code CAPTURING} for any other reason, the fencing is a
 *       no-op and nothing is emitted, so a request that genuinely
 *       succeeded is never silently overwritten. Any other GET failure
 *       (timeout, an unqualified 404, an ambiguous 422, a bare 5xx) proves
 *       nothing and is left untouched.</li>
 * </ul>
 *
 * <p>Every write here is a single guarded conditional UPDATE
 * (never a bulk unconditional write), so two overlapping sweep passes over
 * the same row — a paused/resumed worker, a restart after crash, or (should
 * it ever run with more than one scheduled consumer instance) a genuinely
 * concurrent second sweep — can race freely: at most one of them ever
 * claims a given transition, and every downstream write it enables
 * (capture dispatch, capture recording, event emission) is itself gated the
 * same way.
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);
    private static final List<PaymentState> DUE_STATES = List.of(PaymentState.OPEN, PaymentState.CAPTURING);

    private final PaymentRepository repository;
    private final PayPalClient payPalClient;
    private final PaymentService paymentService;
    private final DomainEventRecorder recorder;
    private final OrderCaptureAuthorizationClient orderClient;
    private final Duration paymentTimeout;
    private final int batchSize;
    private final TransactionTemplate transactions;
    private Instant cursorCreatedAt;
    private String cursorOrderId;

    public PaymentReconciliationService(PaymentRepository repository, PayPalClient payPalClient,
            PaymentService paymentService, DomainEventRecorder recorder, OrderCaptureAuthorizationClient orderClient,
            PlatformTransactionManager transactionManager,
            @Value("${rebyte.checkout.payment-timeout:PT30M}") Duration paymentTimeout,
            @Value("${rebyte.checkout.reconciliation.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.payPalClient = payPalClient;
        this.paymentService = paymentService;
        this.recorder = recorder;
        this.orderClient = orderClient;
        this.paymentTimeout = paymentTimeout;
        this.batchSize = batchSize;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${rebyte.checkout.reconciliation.fixed-delay-ms:60000}",
            initialDelayString = "${rebyte.checkout.reconciliation.initial-delay-ms:15000}")
    public void sweep() {
        try {
            sweepOnce();
        } catch (Exception failure) {
            log.error("Payment reconciliation sweep tick failed", failure);
        }
    }

    /** One bounded batch: reconcile every due payment, then trigger the offer-expiry hook exactly once. */
    public synchronized SweepResult sweepOnce() {
        Instant cutoff = Instant.now().minus(paymentTimeout);
        List<Payment> due = repository.findDueForReconciliation(DUE_STATES, cutoff,
                cursorCreatedAt, cursorOrderId, PageRequest.of(0, batchSize));
        for (Payment payment : due) {
            try {
                reconcileOne(payment);
            } catch (Exception failure) {
                log.warn("Reconciliation failed for order {}; retained for the next cycle",
                        payment.getOrderId(), failure);
            }
        }
        if (due.size() == batchSize) {
            Payment last = due.get(due.size() - 1);
            cursorCreatedAt = last.getCreatedAt();
            cursorOrderId = last.getOrderId();
        } else {
            cursorCreatedAt = null;
            cursorOrderId = null;
        }
        int expiredOffers = expireDueOffers();
        return new SweepResult(due.size(), expiredOffers);
    }

    private void reconcileOne(Payment payment) {
        if (payment.getState() == PaymentState.CAPTURING && payment.getProviderCaptureId() != null) {
            // Already known from our own capture-time response; no PayPal call needed.
            repository.flagCapturedAwaitingWebhook(payment.getOrderId(), payment.getProviderCaptureStatus());
            return;
        }
        if (payment.getState() == PaymentState.OPEN && payment.getProviderOrderId() == null) {
            // PayPal order create itself never recorded a provider id — nothing to GET,
            // nothing that could ever have been captured.
            expireUnclaimed(payment);
            return;
        }

        OrderResponse order;
        try {
            order = payPalClient.getOrder(payment.getProviderOrderId());
        } catch (PayPalProviderException failure) {
            if (isTerminalNoChargeProof(failure)) {
                if (payment.getState() == PaymentState.CAPTURING) {
                    fenceTerminalNoCharge(payment, failure.getProviderErrorName());
                } else {
                    expireUnclaimed(payment);
                }
            }
            // Timeout, unqualified 404, ambiguous 422, bare 5xx: not terminal proof — retain the guard.
            return;
        }

        applyProviderStatus(payment, order.status());
    }

    private void applyProviderStatus(Payment payment, String status) {
        if ("APPROVED".equals(status)) {
            attemptCapture(payment);
        } else if ("COMPLETED".equals(status)) {
            repository.flagCapturedAwaitingWebhook(payment.getOrderId(), "COMPLETED");
        } else if (payment.getState() == PaymentState.OPEN) {
            // CREATED / PAYER_ACTION_REQUIRED / SAVED past the timeout: the buyer
            // never approved it, and it was never claimed for capture.
            expireUnclaimed(payment);
        }
        // CAPTURING + anything else (e.g. a stale/odd CREATED read): explicitly not
        // terminal proof for an already-claimed operation — retain the guard.
    }

    /**
     * Reuses {@link PaymentService#capture(String, String)} verbatim with
     * the payment's own persisted buyer — every guard in that method
     * (claim, grant, recheck, one-time dispatch fence) applies identically,
     * whether {@code payment} started {@code OPEN} (fresh capture) or was
     * already {@code CAPTURING} with no dispatch yet recorded ("lost
     * grant" recovery: the claim and any repeat grant call are both
     * idempotent no-ops, and dispatch is attempted for the first time).
     */
    private void attemptCapture(Payment payment) {
        try {
            paymentService.capture(payment.getOrderId(), payment.getBuyerSub());
        } catch (PaymentClosedException | CaptureDeniedException resolvedElsewhere) {
            // Already closed, already fenced TERMINAL_NO_CHARGE, or denied by
            // order-service — another actor already resolved this payment this cycle.
        }
    }

    /** D-25: an unclaimed, never-approved (or now-gone) payment expires — never reopened once CLOSED. */
    private void expireUnclaimed(Payment payment) {
        transactions.executeWithoutResult(status -> {
            int updated = repository.close(payment.getOrderId(), PaymentState.OPEN, PaymentState.CLOSED);
            if (updated == 1) {
                recorder.record("payment.expired", payment, UUID.randomUUID().toString());
            }
        });
        // updated == 0: a capture claimed this payment between our read and this
        // close attempt (no longer "unclaimed") — no expiry, no event.
    }

    /** D-25/D-26: correlated provider-terminal no-charge proof fences the claim and emits exactly one event. */
    private void fenceTerminalNoCharge(Payment payment, String lastStatus) {
        transactions.executeWithoutResult(status -> {
            int updated = repository.fenceTerminalNoCharge(payment.getOrderId(), PaymentState.CAPTURING,
                    PaymentState.TERMINAL_NO_CHARGE, lastStatus);
            if (updated == 1) {
                recorder.record("payment.expired", payment, UUID.randomUUID().toString());
            }
        });
        // updated == 0: the payment already moved away from CAPTURING (a capture
        // response landed, or it was already fenced/closed) — no second event.
    }

    /**
     * The only terminal no-charge proof this service accepts: PayPal's
     * documented "Resource not found" response for a genuinely gone order
     * resource (03-SANDBOX-SPIKE.md GET-AFTER-EXPIRY; PayPal REST API
     * troubleshooting "Resource not found > INVALID_RESOURCE_ID": "the
     * resource ID specified in the API request does not exist in the
     * system"). Age alone, or an unqualified 404 (wrong name or missing the
     * specific issue), is deliberately insufficient.
     */
    private boolean isTerminalNoChargeProof(PayPalProviderException failure) {
        return failure.getProviderStatus() != null
                && failure.getProviderStatus().value() == 404
                && "RESOURCE_NOT_FOUND".equals(failure.getProviderErrorName())
                && failure.getProviderIssues().contains("INVALID_RESOURCE_ID");
    }

    /** D-25 amendment: triggers order-service's accepted-offer expiry hook once per cycle; never reads order_db. */
    private int expireDueOffers() {
        try {
            int expiredCount = orderClient.expireDueOffers().expiredCount();
            if (expiredCount > 0) {
                log.info("Reconciliation sweep expired {} accepted-offer hold(s)", expiredCount);
            }
            return expiredCount;
        } catch (RuntimeException failure) {
            log.warn("Offer expiry hook call failed; will retry next cycle", failure);
            return 0;
        }
    }

    public record SweepResult(int reconciledCount, int expiredOfferCount) {
    }
}
