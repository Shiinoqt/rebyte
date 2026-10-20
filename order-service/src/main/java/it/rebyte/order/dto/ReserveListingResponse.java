package it.rebyte.order.dto;

/**
 * order-service's own copy of catalog-service's {@code ReserveListingResponse}
 * contract (catalog-service/src/main/java/it/rebyte/catalog/dto/ReserveListingResponse.java) —
 * outcome names must match catalog's JSON exactly for Feign deserialization.
 */
public record ReserveListingResponse(Outcome outcome, String reservationId) {
    public enum Outcome { RESERVED, ALREADY_TAKEN, NOT_FOUND, NOT_RESERVABLE }
}
