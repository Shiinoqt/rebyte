package it.rebyte.order.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

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

    @Column(name = "rating", nullable = false, updatable = false)
    private byte rating;

    @Column(name = "comment", length = 2000, updatable = false)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Review() {
    }

    public Review(String id, Order order, byte rating, String comment, Instant createdAt) {
        this.id = id;
        this.orderId = order.getId();
        this.buyerSub = order.getBuyerSub();
        this.sellerSub = order.getSellerSub();
        this.listingId = order.getListingId();
        this.listingTitle = order.getListingTitle();
        this.rating = rating;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getBuyerSub() { return buyerSub; }
    public String getSellerSub() { return sellerSub; }
    public String getListingId() { return listingId; }
    public String getListingTitle() { return listingTitle; }
    public byte getRating() { return rating; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
}
