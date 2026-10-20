package it.rebyte.catalog.dto;

/** reservationId must be echoed in cancellation/refund events to fence stale releases. */
public record ReserveListingResponse(Outcome outcome, String reservationId) {
    public enum Outcome { RESERVED, ALREADY_TAKEN, NOT_FOUND, NOT_RESERVABLE }
}
