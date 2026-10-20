package it.rebyte.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;

/** Risposta di {@code POST /listings/hardware} — mai il documento Mongo esposto direttamente. */
public record ListingCreatedResponse(
        String id,
        String title,
        BigDecimal price,
        ListingStatus status,
        Instant publication) {

    public static ListingCreatedResponse from(Listing listing) {
        return new ListingCreatedResponse(
                listing.getId(), listing.getTitle(), listing.getPrice(), listing.getStatus(), listing.getPublication());
    }
}
