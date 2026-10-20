package it.rebyte.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A saved listing resolved from Mongo, including terminal statuses absent from search. */
public record FavouriteEntry(
        String listingId,
        String title,
        BigDecimal price,
        List<String> photos,
        String coverUrl,
        FavouriteListingStatus status,
        Instant createdAt) { }
