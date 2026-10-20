package it.rebyte.order.dto;

import java.math.BigDecimal;

import it.rebyte.order.domain.PaymentMethod;

/**
 * {@code POST /internal/payments} request (D-01, D-36 preview). {@code
 * buyerSub} is carried verbatim from the authenticated order snapshot,
 * never re-derived from anything client-suppliable — payment-service must
 * never accept a caller-chosen buyer identity for the purchase unit it
 * builds. payment-service does not exist yet (03-05); this is
 * order-service's half of the contract, stubbed in this plan's *IT classes.
 */
public record PaymentCreateRequest(String orderId, String buyerSub, BigDecimal itemPrice, BigDecimal shippingCost,
        BigDecimal pricePaid, String listingTitle, PaymentMethod method) {
}
