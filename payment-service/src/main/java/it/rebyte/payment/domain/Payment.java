package it.rebyte.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * payment_db.payments (03-CONTEXT.md D-01, D-02, D-21, D-23, D-36). See
 * {@code V1__payments.sql} for column-by-column rationale.
 *
 * <p>{@code orderId} (order-service's own order id, never DB-generated
 * here) is the primary key: every payment-service endpoint in this plan is
 * addressed by order id ({@code POST /internal/payments}, {@code POST
 * /payments/{orderId}/capture}, {@code POST /internal/payments/{orderId}/close}),
 * and D-01's synchronous flow creates exactly one Payment per order.
 *
 * <p>Never exposed by a controller — only DTOs ({@link
 * it.rebyte.payment.dto.PaymentCreateResponse}, {@link
 * it.rebyte.payment.dto.CaptureResponse}), per the project's layering rule.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "order_id", length = 36, nullable = false, updatable = false)
    private String orderId;

    /** Immutable authenticated buyer sub, carried verbatim from order-service (never re-derived, never client-suppliable). */
    @Column(name = "buyer_sub", length = 64, nullable = false, updatable = false)
    private String buyerSub;

    @Column(name = "item_price", nullable = false, updatable = false)
    private BigDecimal itemPrice;

    @Column(name = "shipping_cost", nullable = false, updatable = false)
    private BigDecimal shippingCost;

    @Column(name = "price_paid", nullable = false, updatable = false)
    private BigDecimal pricePaid;

    @Column(name = "listing_title", length = 255, nullable = false, updatable = false)
    private String listingTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 20, nullable = false, updatable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 20, nullable = false)
    private PaymentState state;

    /** PayPal's own order id, set once {@link it.rebyte.payment.provider.PayPalClient#createOrder} succeeds. */
    @Column(name = "provider_order_id", length = 64)
    private String providerOrderId;

    /** Deterministic {@code PayPal-Request-Id} persisted BEFORE the first create call, replayed verbatim on retry — never regenerated. */
    @Column(name = "create_request_id", length = 64, nullable = false, updatable = false)
    private String createRequestId;

    @Column(name = "approval_url", length = 500)
    private String approvalUrl;

    /** payment-service's own durable claim id, minted once by {@link it.rebyte.payment.repository.PaymentRepository#claimCapturing} and sent to order-service as {@code operationId}. */
    @Column(name = "capture_operation_id", length = 64)
    private String captureOperationId;

    /** A second, independent {@code PayPal-Request-Id} for the actual provider capture call — persisted alongside {@link #captureOperationId}, replayed verbatim on retry. */
    @Column(name = "capture_request_id", length = 64)
    private String captureRequestId;

    @Column(name = "capture_dispatched_at")
    private Instant captureDispatchedAt;

    @Column(name = "provider_capture_id", length = 64)
    private String providerCaptureId;

    @Column(name = "provider_capture_status", length = 32)
    private String providerCaptureStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Last PayPal order/capture status word observed by the reconciliation sweep or a fencing decision (D-29). */
    @Column(name = "last_paypal_status", length = 32)
    private String lastPayPalStatus;

    /** True from the moment the sweep observes a PayPal COMPLETED capture with no confirmed webhook yet (D-26/D-29); cleared by {@link #markWebhookConfirmed}. */
    @Column(name = "captured_awaiting_webhook", nullable = false)
    private boolean capturedAwaitingWebhook;

    /** Set once a real, verified PayPal webhook resolves this payment's outcome; excludes the row from further reconciliation sweeps. */
    @Column(name = "webhook_confirmed_at")
    private Instant webhookConfirmedAt;

    /** Mandatory admin-supplied reason (03-10-PLAN.md Task 2, D-27), persisted once with the refund claim. */
    @Column(name = "refund_reason", length = 500)
    private String refundReason;

    /** Deterministic {@code PayPal-Request-Id} for the refund call, persisted BEFORE dispatch and reused verbatim on every repeat request — never regenerated. */
    @Column(name = "refund_request_id", length = 64)
    private String refundRequestId;

    /** Durable one-attempt fence: set before the PayPal refund request so a repeat/racing request never issues a second call. */
    @Column(name = "refund_dispatched_at")
    private Instant refundDispatchedAt;

    protected Payment() {
        // required by JPA
    }

    public Payment(String orderId, String buyerSub, BigDecimal itemPrice, BigDecimal shippingCost,
            BigDecimal pricePaid, String listingTitle, PaymentMethod method, String createRequestId,
            Instant createdAt) {
        this.orderId = orderId;
        this.buyerSub = buyerSub;
        this.itemPrice = itemPrice;
        this.shippingCost = shippingCost;
        this.pricePaid = pricePaid;
        this.listingTitle = listingTitle;
        this.method = method;
        this.state = PaymentState.OPEN;
        this.createRequestId = createRequestId;
        this.createdAt = createdAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getBuyerSub() {
        return buyerSub;
    }

    public BigDecimal getItemPrice() {
        return itemPrice;
    }

    public BigDecimal getShippingCost() {
        return shippingCost;
    }

    public BigDecimal getPricePaid() {
        return pricePaid;
    }

    public String getListingTitle() {
        return listingTitle;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentState getState() {
        return state;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getCreateRequestId() {
        return createRequestId;
    }

    public String getApprovalUrl() {
        return approvalUrl;
    }

    public String getCaptureOperationId() {
        return captureOperationId;
    }

    public String getCaptureRequestId() {
        return captureRequestId;
    }

    public String getProviderCaptureId() {
        return providerCaptureId;
    }

    public String getProviderCaptureStatus() {
        return providerCaptureStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCaptureDispatchedAt() {
        return captureDispatchedAt;
    }

    public String getLastPayPalStatus() {
        return lastPayPalStatus;
    }

    public boolean isCapturedAwaitingWebhook() {
        return capturedAwaitingWebhook;
    }

    public Instant getWebhookConfirmedAt() {
        return webhookConfirmedAt;
    }

    public String getRefundReason() {
        return refundReason;
    }

    public String getRefundRequestId() {
        return refundRequestId;
    }

    public Instant getRefundDispatchedAt() {
        return refundDispatchedAt;
    }

    /**
     * D-27: a refund is only ever attempted on a capture THIS service has
     * itself seen confirmed by a real, verified webhook — never on the
     * synchronous capture response alone (mirrors D-26's "no capture result
     * ... ever marks the order PAID" for the refund side: no local capture
     * write ever makes a payment refund-eligible by itself).
     */
    public boolean isVerifiedCapture() {
        return providerCaptureId != null && "COMPLETED".equals(providerCaptureStatus) && webhookConfirmedAt != null;
    }

    /**
     * Called only once a genuine, verified webhook resolves this payment's
     * outcome (03-10-PLAN.md Task 1, D-26/D-29) — never by the reconciliation
     * sweep itself, which only ever sets {@link #capturedAwaitingWebhook}.
     * Excludes this row from future reconciliation sweeps and clears the
     * "awaiting webhook" flag, since the outcome is now confirmed either way.
     */
    public void markWebhookConfirmed(Instant at) {
        this.webhookConfirmedAt = at;
        this.capturedAwaitingWebhook = false;
    }

    public boolean applyCaptureEvent(String captureId, String captureStatus) {
        if (providerCaptureId != null && !providerCaptureId.equals(captureId)) {
            throw new IllegalArgumentException("Capture does not match payment");
        }
        if (captureStatus.equals(providerCaptureStatus)) {
            // The synchronous capture response already recorded this status, so the
            // first verified webhook for it is still the confirmation of the outcome.
            return webhookConfirmedAt == null && !"PENDING".equals(captureStatus);
        }
        if ("REFUNDED".equals(providerCaptureStatus) || "DENIED".equals(providerCaptureStatus)) {
            return false;
        }
        if ("COMPLETED".equals(providerCaptureStatus) && !"REFUNDED".equals(captureStatus)) {
            return false;
        }
        providerCaptureId = captureId;
        providerCaptureStatus = captureStatus;
        return true;
    }
}
