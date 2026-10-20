package it.rebyte.order.domain;

/**
 * The checkout operation gate (03-CONTEXT.md D-16 amendment), deliberately
 * separate from {@link OrderStatus}: {@code status} is the buyer-visible
 * order lifecycle from the binding model, while {@code checkoutPhase} is an
 * order-service-internal claim on "who may act next" during the
 * guard/reserve/payment-create sequence and the later capture handshake
 * with payment-service.
 *
 * <ul>
 *   <li>{@code CREATING} — the guard row is committed but the approval URL
 *       is not yet saved; a failed payment-create must never be allowed to
 *       revive a {@code CANCELLED} order past this point.</li>
 *   <li>{@code READY} — the approval URL is saved; reached only via the
 *       guarded {@code CREATING -> READY} transition
 *       ({@link it.rebyte.order.repository.OrderRepository#transitionPhase}).</li>
 *   <li>{@code CAPTURE_AUTHORIZED} — payment-service was granted the
 *       capture operation claim via
 *       {@code POST /internal/orders/{id}/capture-authorizations}.</li>
 *   <li>{@code CANCELLED} — compensation ran (guard cleared); mirrors, but
 *       is not the same axis as, {@link OrderStatus#CANCELLED}.</li>
 * </ul>
 */
public enum CheckoutPhase {
    CREATING,
    READY,
    CAPTURE_AUTHORIZED,
    CANCELLED
}
