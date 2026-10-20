package it.rebyte.payment.dto;

/**
 * {@code POST /payments/{orderId}/capture} response (D-02): the buyer only
 * ever sees "capture accepted, awaiting confirmation" — this endpoint never
 * returns a status meaning the order is paid. The webhook is the only path
 * to {@code PAID} (D-26), built in a later plan; the buyer's client polls
 * {@code GET /orders/{id}} for that.
 */
public record CaptureResponse(String orderId, Status status) {

    public enum Status {
        ACCEPTED_AWAITING_CONFIRMATION
    }
}
