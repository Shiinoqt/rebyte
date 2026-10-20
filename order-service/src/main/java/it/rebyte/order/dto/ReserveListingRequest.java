package it.rebyte.order.dto;

/**
 * order-service's own copy of catalog-service's {@code ReserveListingRequest}
 * contract — field names ({@code reservationId}, {@code expectedVersion})
 * must match exactly for JSON serialization onto
 * {@code PATCH /internal/listings/{id}/reserve}. {@code reservationId} is
 * always this order's own {@code id} (D-17): orderId IS the reservationId
 * for a buy-it-now checkout.
 */
public record ReserveListingRequest(String reservationId, Long expectedVersion) {
}
