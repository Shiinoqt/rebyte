package it.rebyte.user.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "seller_sale")
public class SellerSale {

    @Id
    @Column(name = "order_id", length = 36, nullable = false, updatable = false)
    private String orderId;

    @Column(name = "seller_sub", length = 64, nullable = false, updatable = false)
    private String sellerSub;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    protected SellerSale() {
        // required by JPA
    }

    public String getOrderId() {
        return orderId;
    }

    public String getSellerSub() {
        return sellerSub;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}
