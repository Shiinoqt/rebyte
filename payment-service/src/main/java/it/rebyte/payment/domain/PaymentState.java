package it.rebyte.payment.domain;

/**
 * payment_db's own operation gate (03-CONTEXT.md D-02 amendment, Task 2's
 * transaction recommendation 5), deliberately separate from order-service's
 * {@code CheckoutPhase} — the two services never share a state machine or a
 * database.
 *
 * <ul>
 *   <li>{@code OPEN} — the payment row exists (create succeeded or is
 *       pending); capture has not been claimed.</li>
 *   <li>{@code CAPTURING} — the buyer's capture request claimed the
 *       operation exactly once via the guarded {@code OPEN -> CAPTURING}
 *       transition ({@link it.rebyte.payment.repository.PaymentRepository#claimCapturing}).
 *       Every repeat request for the same order reuses the persisted {@code
 *       captureOperationId}/{@code captureRequestId} rather than minting new
 *       ones (D-02: "reused only within the provider-supported retry
 *       window").</li>
 *   <li>{@code CLOSED} — committed only while {@code OPEN} (never from
 *       {@code CAPTURING}); a close request racing a capture claim reports
 *       conflict/unknown rather than silently overwriting the claim.</li>
 *   <li>{@code TERMINAL_NO_CHARGE} — committed only from {@code CAPTURING}
 *       (03-10-PLAN.md Task 1), by {@link
 *       it.rebyte.payment.service.PaymentReconciliationService} alone, after
 *       correlated PayPal evidence proves the claimed capture operation can
 *       never charge (the provider order resource itself is gone — the same
 *       resource id a delayed/in-flight capture call would also target, so
 *       that call cannot succeed either). Treated identically to {@code
 *       CLOSED} by every capture guard: never reopened.</li>
 * </ul>
 */
public enum PaymentState {
    OPEN,
    CAPTURING,
    CLOSED,
    TERMINAL_NO_CHARGE
}
