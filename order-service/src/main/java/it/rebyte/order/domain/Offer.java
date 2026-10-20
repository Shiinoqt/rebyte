package it.rebyte.order.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "offers")
public class Offer {
    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;
    @Column(name = "listing_id", length = 36, nullable = false, updatable = false)
    private String listingId;
    @Column(name = "buyer_sub", length = 64, nullable = false, updatable = false)
    private String buyerSub;
    @Column(name = "seller_sub", length = 64, nullable = false, updatable = false)
    private String sellerSub;
    @Column(name = "listing_title", length = 255, nullable = false, updatable = false)
    private String listingTitle;
    @Column(name = "asking_price", precision = 10, scale = 2, nullable = false, updatable = false)
    private BigDecimal askingPrice;
    @Column(name = "amount", precision = 10, scale = 2, nullable = false, updatable = false)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OfferStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 32)
    private RejectionReason rejectionReason;
    @Column(name = "pending_guard", length = 36)
    private String pendingGuard;
    @Column(name = "hold_reservation_id", length = 36)
    private String holdReservationId;
    @Column(name = "accept_claimed_at")
    private Instant acceptClaimedAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "order_id", length = 36)
    private String orderId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Offer() {
    }

    public Offer(String id, String listingId, String buyerSub, String sellerSub, String listingTitle,
            BigDecimal askingPrice, BigDecimal amount, Instant createdAt) {
        this.id = id;
        this.listingId = listingId;
        this.buyerSub = buyerSub;
        this.sellerSub = sellerSub;
        this.listingTitle = listingTitle;
        this.askingPrice = askingPrice;
        this.amount = amount;
        this.status = OfferStatus.PENDING;
        this.pendingGuard = listingId;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getListingId() { return listingId; }
    public String getBuyerSub() { return buyerSub; }
    public String getSellerSub() { return sellerSub; }
    public String getListingTitle() { return listingTitle; }
    public BigDecimal getAskingPrice() { return askingPrice; }
    public BigDecimal getAmount() { return amount; }
    public OfferStatus getStatus() { return status; }
    public RejectionReason getRejectionReason() { return rejectionReason; }
    public String getPendingGuard() { return pendingGuard; }
    public String getHoldReservationId() { return holdReservationId; }
    public Instant getAcceptClaimedAt() { return acceptClaimedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getOrderId() { return orderId; }
    public Instant getCreatedAt() { return createdAt; }
}
