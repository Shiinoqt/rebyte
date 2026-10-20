package it.rebyte.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.domain.Platform;

public record ListingCard(String id, String title, BigDecimal price, ListingType type, ListingStatus status,
        String coverUrl, Condition condition, String shippingLocation, List<Platform> platforms,
        String currentVersion, Instant publication) {
}
