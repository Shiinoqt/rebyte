package it.rebyte.payment.dto;

/**
 * {@code POST /internal/payments/{orderId}/refund} response (03-10-PLAN.md
 * Task 2, D-27): the caller only ever sees "refund pending" — this endpoint
 * never itself reports the refund as complete. {@code
 * PAYMENT.CAPTURE.REFUNDED} is the only path to {@code payment.refunded}
 * (mirrors {@link CaptureResponse}'s {@code ACCEPTED_AWAITING_CONFIRMATION}
 * for the capture side).
 */
public record RefundResponse(String orderId, Status status) {

    public enum Status {
        REFUND_PENDING
    }
}
