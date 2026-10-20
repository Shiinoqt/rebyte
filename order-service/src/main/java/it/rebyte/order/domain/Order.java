package it.rebyte.order.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * order_db.orders (03-CONTEXT.md D-01, D-04, D-09, D-10, D-13, D-16, D-17,
 * D-24, D-35; 03.1-CONTEXT.md D-07, D-08, D-12, D-21). See
 * {@code V1__orders.sql}/{@code V4__order_offer_link.sql} for column-by-
 * column rationale.
 *
 * <p>{@code id} is a {@link java.util.UUID#randomUUID()} string minted by
 * order-service itself (never DB-generated). It doubles as the {@code
 * reservationId} sent to catalog-service's reserve contract only for a
 * buy-it-now checkout (D-17); an offer checkout persists the accepted
 * offer's own {@code holdReservationId} instead via {@link #linkOffer}
 * (Phase 3.1 D-07, D-12).
 *
 * <p>Address fields are flat columns rather than an {@code @Embeddable}:
 * the model's {@code Address} data type (docs/rebyte_progettazione.md
 * §5.1) has no identity of its own, and the DTO layer
 * ({@link it.rebyte.order.dto.CheckoutRequest.Address}) already gives it
 * its data-type shape at the API boundary — a second embeddable type would
 * be an abstraction with no second real implementation (CLAUDE.md).
 * Present if and only if {@link #listingType} is {@link ListingType#HARDWARE}
 * (V.Order.shippingAddressIffHardware).
 *
 * <p>Never exposed by a controller — only DTOs
 * ({@link it.rebyte.order.dto.CheckoutResponse}), per the project's
 * layering rule.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "buyer_sub", length = 64, nullable = false, updatable = false)
    private String buyerSub;

    @Column(name = "seller_sub", length = 64, nullable = false, updatable = false)
    private String sellerSub;

    @Column(name = "listing_id", length = 36, nullable = false, updatable = false)
    private String listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "listing_type", length = 20, nullable = false, updatable = false)
    private ListingType listingType;

    @Column(name = "listing_title", length = 255, nullable = false, updatable = false)
    private String listingTitle;

    @Column(name = "license_key_pattern", length = 64, updatable = false)
    private String licenseKeyPattern;

    @Column(name = "item_price", nullable = false, updatable = false)
    private BigDecimal itemPrice;

    @Column(name = "shipping_cost", nullable = false, updatable = false)
    private BigDecimal shippingCost;

    @Column(name = "price_paid", nullable = false, updatable = false)
    private BigDecimal pricePaid;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20, nullable = false, updatable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "checkout_phase", length = 20, nullable = false)
    private CheckoutPhase checkoutPhase;

    /** listingId while a HARDWARE order is live; NULL once cancelled/refunded or for SOFTWARE (D-16). */
    @Column(name = "active_listing_guard", length = 36)
    private String activeListingGuard;

    /**
     * The persisted identity of the catalog hold this order owns: this
     * order's own {@link #id} for buy-it-now (set at construction for
     * HARDWARE), or the accepted offer's {@code holdReservationId} once
     * {@link #linkOffer} runs (Phase 3.1 D-07, D-12). Cleared to null while
     * an offer order is cancelled within its still-valid hold, so the
     * release event carries no reservation (D-08, D-21).
     */
    @Column(name = "reservation_id", length = 36)
    private String reservationId;

    /** The offer this order originated from once checkout resolves a price from an accepted offer (D-12); null for buy-it-now. */
    @Column(name = "offer_id", length = 36, updatable = false)
    private String offerId;

    @Column(name = "approval_url", length = 500)
    private String approvalUrl;

    @Column(name = "provider_order_id", length = 64)
    private String providerOrderId;

    @Column(name = "provider_capture_id", length = 64)
    private String providerCaptureId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 32)
    private CancellationReason cancellationReason;

    /** The claim id granted by POST /internal/orders/{id}/capture-authorizations. */
    @Column(name = "capture_operation_id", length = 64)
    private String captureOperationId;

    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expiry_at", nullable = false, updatable = false)
    private Instant expiryAt;

    @Column(name = "recipient_name", length = 150)
    private String recipientName;

    @Column(name = "street", length = 200)
    private String street;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "refund_requested_at")
    private Instant refundRequestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier", length = 20)
    private Carrier carrier;

    @Column(name = "tracking_code", length = 64)
    private String trackingCode;

    protected Order() {
        // required by JPA
    }

    public Order(String id, String buyerSub, String sellerSub, String listingId, ListingType listingType,
            String listingTitle, BigDecimal itemPrice, BigDecimal shippingCost, BigDecimal pricePaid,
            PaymentMethod method, String idempotencyKey, String requestFingerprint, Instant createdAt,
            Instant expiryAt) {
        this.id = id;
        this.buyerSub = buyerSub;
        this.sellerSub = sellerSub;
        this.listingId = listingId;
        this.listingType = listingType;
        this.listingTitle = listingTitle;
        this.itemPrice = itemPrice;
        this.shippingCost = shippingCost;
        this.pricePaid = pricePaid;
        this.method = method;
        this.status = OrderStatus.AWAITING_PAYMENT;
        this.checkoutPhase = CheckoutPhase.CREATING;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt;
        this.expiryAt = expiryAt;
        this.reservationId = listingType == ListingType.HARDWARE ? id : null;
    }

    /**
     * Links this order to the offer it converted from, adopting the offer's
     * persisted hold id as this order's reservation identity (Phase 3.1
     * D-07, D-12).
     */
    public void linkOffer(String offerId, String holdReservationId) {
        this.offerId = offerId;
        this.reservationId = holdReservationId;
    }

    public void applyShippingAddress(String recipientName, String street, String city, String postalCode,
            String country) {
        this.recipientName = recipientName;
        this.street = street;
        this.city = city;
        this.postalCode = postalCode;
        this.country = country;
    }

    public String getId() {
        return id;
    }

    public String getBuyerSub() {
        return buyerSub;
    }

    public String getSellerSub() {
        return sellerSub;
    }

    public String getListingId() {
        return listingId;
    }

    public ListingType getListingType() {
        return listingType;
    }

    public String getListingTitle() {
        return listingTitle;
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

    public PaymentMethod getMethod() {
        return method;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public CheckoutPhase getCheckoutPhase() {
        return checkoutPhase;
    }

    public String getActiveListingGuard() {
        return activeListingGuard;
    }

    public void setActiveListingGuard(String activeListingGuard) {
        this.activeListingGuard = activeListingGuard;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public String getOfferId() {
        return offerId;
    }

    public String getApprovalUrl() {
        return approvalUrl;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getProviderCaptureId() {
        return providerCaptureId;
    }

    public CancellationReason getCancellationReason() {
        return cancellationReason;
    }

    public void markPaid(String captureId, Instant paidAt) {
        this.paidAt = paidAt;
        status = OrderStatus.PAID;
        providerCaptureId = captureId;
    }

    public void snapshotLicenseKeyPattern(String pattern) {
        this.licenseKeyPattern = pattern;
    }

    public String getLicenseKeyPattern() {
        return licenseKeyPattern;
    }

    public void markDelivered(Instant at) {
        status = OrderStatus.DELIVERED;
        deliveredAt = at;
    }

    public void markCancelled(CancellationReason reason) {
        status = OrderStatus.CANCELLED;
        checkoutPhase = CheckoutPhase.CANCELLED;
        activeListingGuard = null;
        cancellationReason = reason;
    }

    public void markRefunded() {
        status = OrderStatus.REFUNDED;
        activeListingGuard = null;
    }

    public String getCaptureOperationId() {
        return captureOperationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiryAt() {
        return expiryAt;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getStreet() {
        return street;
    }

    public String getCity() {
        return city;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public Instant getPaidAt() { return paidAt; }

    public Instant getShippedAt() { return shippedAt; }

    public Instant getDeliveredAt() { return deliveredAt; }

    public Instant getRefundRequestedAt() { return refundRequestedAt; }

    public Carrier getCarrier() { return carrier; }

    public String getTrackingCode() { return trackingCode; }

    public String getCountry() {
        return country;
    }
}
