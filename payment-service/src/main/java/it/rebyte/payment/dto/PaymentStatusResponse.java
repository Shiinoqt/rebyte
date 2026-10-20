package it.rebyte.payment.dto;

/**
 * {@code GET /internal/payments/{orderId}/status} response (03-11-PLAN.md
 * orchestrator-directed wiring fix, closing 03-10's D-29 gap). Read-only
 * diagnostic snapshot for order-service's admin stuck-order view: never the
 * {@link it.rebyte.payment.domain.Payment} entity itself, only the fields
 * D-29's three stuck categories need — order-service classifies by its OWN
 * {@code checkoutPhase} and cross-references {@code capturedAwaitingWebhook}
 * here rather than order-service reading payment_db directly (CLAUDE.md: a
 * service must never read another service's database).
 *
 * <p>{@code providerOrderId} is null when PayPal order creation never
 * recorded a provider id — the signal order-service's stuck view uses to
 * classify an order still stuck in {@code checkoutPhase = CREATING} as a
 * failed/unresolved payment create, without payment-service exposing its
 * own {@code PaymentState} enum across the service boundary.
 */
public record PaymentStatusResponse(String orderId, String state, String providerOrderId, String lastPayPalStatus,
        boolean capturedAwaitingWebhook) {
}
