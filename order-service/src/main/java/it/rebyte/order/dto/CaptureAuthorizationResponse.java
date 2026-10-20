package it.rebyte.order.dto;

/**
 * {@code POST /internal/orders/{orderId}/capture-authorizations} response.
 * {@code checkoutPhase} always reflects the order's final phase after the
 * call, whatever the outcome, so a caller never needs a follow-up read to
 * find out what state it is left in.
 */
public record CaptureAuthorizationResponse(Outcome outcome, String checkoutPhase) {
    public enum Outcome { GRANTED, ALREADY_GRANTED, DENIED }
}
