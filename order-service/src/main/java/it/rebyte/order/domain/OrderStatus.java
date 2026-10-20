package it.rebyte.order.domain;

/**
 * docs/rebyte_progettazione.md §12 skeleton (line 1523), verbatim:
 * {@code public enum OrderStatus { AWAITING_PAYMENT, PAID, SHIPPED, DELIVERED, CANCELLED, REFUNDED }}.
 *
 * <p>Task 1 (03-04-PLAN.md) only ever writes {@code AWAITING_PAYMENT} and
 * {@code CANCELLED} — the payment/capture/webhook plans advance the rest.
 */
public enum OrderStatus {
    AWAITING_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    REFUNDED
}
