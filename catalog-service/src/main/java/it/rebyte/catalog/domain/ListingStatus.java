package it.rebyte.catalog.domain;

/**
 * Stato di un {@link Listing}, esattamente come dichiarato in ch. 12 del
 * modello ristrutturato (D-12, 02-CONTEXT.md) — nessuno stato {@code
 * WITHDRAWN} separato: il ritiro del venditore e la rimozione admin
 * atterrano entrambi su {@code REMOVED}, distinti solo dalla presenza di un
 * {@code Moderation} (piano successivo).
 */
public enum ListingStatus {
    ACTIVE,
    SOLD,
    REMOVED
}
