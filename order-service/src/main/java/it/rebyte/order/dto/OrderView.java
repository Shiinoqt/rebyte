package it.rebyte.order.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import it.rebyte.order.domain.Carrier;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.domain.LicenseStatus;

public record OrderView(String id, String listingId, String listingTitle, ListingType listingType,
        String buyerSub, String sellerSub, BigDecimal itemPrice, BigDecimal shippingCost, BigDecimal pricePaid,
        PaymentMethod paymentMethod, OrderStatus status, Instant createdAt, Instant expiryAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) ShippingAddress shippingAddress,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant paidAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant shippedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant deliveredAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Carrier carrier,
        @JsonInclude(JsonInclude.Include.NON_NULL) String trackingCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant refundRequestedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) LicenseSummary license,
        @JsonInclude(JsonInclude.Include.NON_NULL) ProblemView problem,
        @JsonInclude(JsonInclude.Include.NON_NULL) ReviewView review) {

    public OrderView(String id, String listingId, String listingTitle, ListingType listingType,
            String buyerSub, String sellerSub, BigDecimal itemPrice, BigDecimal shippingCost, BigDecimal pricePaid,
            PaymentMethod paymentMethod, OrderStatus status, Instant createdAt, Instant expiryAt,
            ShippingAddress shippingAddress, Instant paidAt, Instant shippedAt, Instant deliveredAt,
            Carrier carrier, String trackingCode, Instant refundRequestedAt) {
        this(id, listingId, listingTitle, listingType, buyerSub, sellerSub, itemPrice, shippingCost, pricePaid,
                paymentMethod, status, createdAt, expiryAt, shippingAddress, paidAt, shippedAt, deliveredAt,
                carrier, trackingCode, refundRequestedAt, null, null, null);
    }

    public OrderView(String id, String listingId, String listingTitle, ListingType listingType,
            String buyerSub, String sellerSub, BigDecimal itemPrice, BigDecimal shippingCost, BigDecimal pricePaid,
            PaymentMethod paymentMethod, OrderStatus status, Instant createdAt, Instant expiryAt,
            ShippingAddress shippingAddress, Instant paidAt, Instant shippedAt, Instant deliveredAt,
            Carrier carrier, String trackingCode, Instant refundRequestedAt, LicenseSummary license) {
        this(id, listingId, listingTitle, listingType, buyerSub, sellerSub, itemPrice, shippingCost, pricePaid,
                paymentMethod, status, createdAt, expiryAt, shippingAddress, paidAt, shippedAt, deliveredAt,
                carrier, trackingCode, refundRequestedAt, license, null, null);
    }

    public record ShippingAddress(String recipient, String street, String city, String postalCode, String country) {
    }

    public record LicenseSummary(LicenseStatus status, Instant issuedAt, Instant revokedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String key) {
    }
}
