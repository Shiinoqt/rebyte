package it.rebyte.order.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "licenses")
public class License {

    @Id
    @Column(name = "license_key", length = 64, nullable = false, updatable = false)
    private String key;
    @Column(name = "order_id", length = 36, nullable = false, updatable = false)
    private String orderId;
    @Column(name = "buyer_sub", length = 64, nullable = false, updatable = false)
    private String buyerSub;
    @Column(name = "seller_sub", length = 64, nullable = false, updatable = false)
    private String sellerSub;
    @Column(name = "listing_id", length = 36, nullable = false, updatable = false)
    private String listingId;
    @Column(name = "listing_title", length = 255, nullable = false, updatable = false)
    private String listingTitle;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private LicenseStatus status;
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected License() {
    }

    public License(String key, Order order, Instant issuedAt) {
        this.key = key;
        this.orderId = order.getId();
        this.buyerSub = order.getBuyerSub();
        this.sellerSub = order.getSellerSub();
        this.listingId = order.getListingId();
        this.listingTitle = order.getListingTitle();
        this.status = LicenseStatus.ACTIVE;
        this.issuedAt = issuedAt;
    }

    public String getKey() { return key; }
    public String getOrderId() { return orderId; }
    public String getBuyerSub() { return buyerSub; }
    public String getSellerSub() { return sellerSub; }
    public String getListingId() { return listingId; }
    public String getListingTitle() { return listingTitle; }
    public LicenseStatus getStatus() { return status; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
