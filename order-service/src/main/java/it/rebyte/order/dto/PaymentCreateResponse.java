package it.rebyte.order.dto;

/** {@code POST /internal/payments} response: the PayPal order id and the buyer-facing approval URL (D-01). */
public record PaymentCreateResponse(String paymentId, String approvalUrl) {
}
