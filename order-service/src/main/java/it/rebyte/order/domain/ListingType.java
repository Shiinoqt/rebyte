package it.rebyte.order.domain;

/**
 * order-service's own copy of catalog-service's discriminant enum
 * (docs/rebyte_progettazione.md §5.2). Never shared through
 * {@code it.rebyte.commons} — a service must never depend on another
 * service's domain types (CLAUDE.md). Constant names must stay byte-for-byte
 * identical to {@code catalog-service}'s {@code ListingType} because Feign
 * deserializes catalog's JSON response directly into this type
 * ({@link it.rebyte.order.dto.CatalogListingResponse}).
 */
public enum ListingType {
    HARDWARE,
    SOFTWARE
}
