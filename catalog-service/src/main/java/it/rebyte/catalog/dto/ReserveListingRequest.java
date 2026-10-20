package it.rebyte.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Caller-supplied reservation identity (D-17): {@code orderId} for a
 * buy-it-now checkout, {@code offerId} for a Phase 3.1 accepted offer. The
 * same id repeated against an already-SOLD listing echoes {@code RESERVED}
 * instead of failing, making the reserve call safe to retry. {@code
 * expectedVersion} is the listing {@code version} the caller read before
 * checkout — a price or status edit since that read fences the reserve with
 * {@code NOT_RESERVABLE} rather than silently charging a stale price.
 */
public record ReserveListingRequest(@NotBlank String reservationId, @NotNull Long expectedVersion) {
}
