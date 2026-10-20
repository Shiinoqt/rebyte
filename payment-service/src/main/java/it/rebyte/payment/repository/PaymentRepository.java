package it.rebyte.payment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.domain.PaymentState;
import jakarta.persistence.LockModeType;

/**
 * Access to payment_db.payments (D-01, D-02, D-21). {@code orderId} is the
 * primary key, so {@link #findById(Object)} is the read path used
 * throughout {@link it.rebyte.payment.service.PaymentService}.
 *
 * <p>Every {@code @Modifying} method here is called OUTSIDE any surrounding
 * transaction from {@link it.rebyte.payment.service.PaymentService} on
 * purpose — the capture-authorization grant call to order-service happens
 * between the claim and the PayPal capture call, and no MySQL transaction
 * is ever held across a Feign or RestClient call — so each carries its own
 * {@code @Transactional} to satisfy Spring Data's requirement that a
 * modifying query run inside an active transaction.
 */
public interface PaymentRepository extends JpaRepository<Payment, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findByProviderOrderId(String providerOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findByProviderCaptureId(String providerCaptureId);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Payment p set p.providerOrderId = :providerOrderId, p.approvalUrl = :approvalUrl "
            + "where p.orderId = :orderId and p.providerOrderId is null")
    int recordProviderOrder(@Param("orderId") String orderId,
            @Param("providerOrderId") String providerOrderId, @Param("approvalUrl") String approvalUrl);

    /**
     * The guarded {@code OPEN -> CAPTURING} transition (Task 2, D-02
     * amendment): claims the capture operation exactly once, persisting the
     * caller-minted {@code operationId}/{@code requestId} so every repeat
     * request for the same order reuses the SAME pair rather than minting a
     * new one.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Payment p set p.state = :toState, p.captureOperationId = :operationId, "
            + "p.captureRequestId = :requestId where p.orderId = :orderId and p.state = :fromState")
    int claimCapturing(@Param("orderId") String orderId, @Param("operationId") String operationId,
            @Param("requestId") String requestId, @Param("fromState") PaymentState fromState,
            @Param("toState") PaymentState toState);

    /**
     * Commits {@code CLOSED} only while still {@code OPEN} — a close racing
     * an already-claimed capture (state {@code CAPTURING}) is a no-op here;
     * the caller reads {@code 0} as conflict/unknown, never silently
     * overwriting the capture claim.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Payment p set p.state = :toState where p.orderId = :orderId and p.state = :fromState")
    int close(@Param("orderId") String orderId, @Param("fromState") PaymentState fromState,
            @Param("toState") PaymentState toState);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Payment p set p.captureDispatchedAt = :dispatchedAt where p.orderId = :orderId "
            + "and p.state = :state and p.captureDispatchedAt is null")
    int claimCaptureDispatch(@Param("orderId") String orderId, @Param("state") PaymentState state,
            @Param("dispatchedAt") Instant dispatchedAt);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Payment p set p.providerCaptureId = :captureId, p.providerCaptureStatus = :captureStatus "
            + "where p.orderId = :orderId and p.state = :state and p.providerCaptureId is null")
    int recordCapture(@Param("orderId") String orderId, @Param("state") PaymentState state,
            @Param("captureId") String captureId, @Param("captureStatus") String captureStatus);

    /**
     * Rows the reconciliation sweep still needs to look at (03-10-PLAN.md
     * Task 1): not yet resolved by a real webhook ({@code
     * webhookConfirmedAt is null}), past the configured payment timeout, in
     * one of the two non-terminal states. The stable cursor lets bounded
     * sweeps advance past unresolved rows, including identical timestamps.
     */
    @Query("select p from Payment p where p.state in :states and p.webhookConfirmedAt is null "
            + "and p.createdAt <= :cutoff and (:afterCreatedAt is null or p.createdAt > :afterCreatedAt "
            + "or (p.createdAt = :afterCreatedAt and p.orderId > :afterOrderId)) order by p.createdAt, p.orderId")
    List<Payment> findDueForReconciliation(@Param("states") List<PaymentState> states,
            @Param("cutoff") Instant cutoff, @Param("afterCreatedAt") Instant afterCreatedAt,
            @Param("afterOrderId") String afterOrderId, Pageable page);

    /**
     * The guarded {@code CAPTURING -> TERMINAL_NO_CHARGE} transition: only
     * the reconciliation sweep calls this, and only after correlated
     * provider-terminal no-charge proof (PayPalReconciliationService). A
     * payment already moved away from {@code CAPTURING} (closed, or already
     * fenced) leaves this a no-op — the caller reads {@code 0} and does not
     * emit a second event.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Payment p set p.state = :toState, p.lastPayPalStatus = :lastStatus "
            + "where p.orderId = :orderId and p.state = :fromState and p.providerCaptureId is null")
    int fenceTerminalNoCharge(@Param("orderId") String orderId, @Param("fromState") PaymentState fromState,
            @Param("toState") PaymentState toState, @Param("lastStatus") String lastStatus);

    /** D-26/D-29: records "captured, awaiting webhook" — never changes {@code state}. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Payment p set p.lastPayPalStatus = :status, p.capturedAwaitingWebhook = true "
            + "where p.orderId = :orderId")
    int flagCapturedAwaitingWebhook(@Param("orderId") String orderId, @Param("status") String status);

    /**
     * The one-time refund claim (03-10-PLAN.md Task 2, D-27): mints the
     * stable reason/request-id pair exactly once, and ONLY for a payment
     * this service has itself seen confirmed COMPLETED by a real webhook —
     * the same eligibility {@link it.rebyte.payment.domain.Payment#isVerifiedCapture()}
     * checks, enforced again here at the database level so a race can never
     * claim a refund for a payment that is not (or no longer, e.g. already
     * refunded once) eligible.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Payment p set p.refundReason = :reason, p.refundRequestId = :requestId "
            + "where p.orderId = :orderId and p.refundRequestId is null "
            + "and p.providerCaptureId is not null and p.providerCaptureStatus = 'COMPLETED' "
            + "and p.webhookConfirmedAt is not null")
    int claimRefund(@Param("orderId") String orderId, @Param("reason") String reason,
            @Param("requestId") String requestId);

    /** Durable one-attempt dispatch fence for the refund call, mirroring {@link #claimCaptureDispatch}. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Payment p set p.refundDispatchedAt = :dispatchedAt where p.orderId = :orderId "
            + "and p.refundRequestId is not null and p.refundDispatchedAt is null")
    int claimRefundDispatch(@Param("orderId") String orderId, @Param("dispatchedAt") Instant dispatchedAt);
}
