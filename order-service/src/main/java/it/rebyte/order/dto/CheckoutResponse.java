package it.rebyte.order.dto;

import it.rebyte.order.domain.OrderStatus;

/**
 * {@code POST /orders} response (Task 1, 03-04-PLAN.md): the order id, the
 * PayPal approval URL the buyer must open, and the resulting status —
 * always {@link OrderStatus#AWAITING_PAYMENT} for a freshly-created order
 * (D-01: synchronous end to end, no async create-then-poll).
 */
public record CheckoutResponse(String orderId, String approvalUrl, OrderStatus status) {
}
