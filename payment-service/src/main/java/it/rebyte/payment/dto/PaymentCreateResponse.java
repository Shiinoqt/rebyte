package it.rebyte.payment.dto;

/**
 * {@code POST /internal/payments} response: PayPal's own order id and the
 * buyer-facing approval URL (D-01). Field names match order-service's
 * {@code PaymentCreateResponse} exactly for Feign deserialization.
 */
public record PaymentCreateResponse(String paymentId, String approvalUrl) {
}
