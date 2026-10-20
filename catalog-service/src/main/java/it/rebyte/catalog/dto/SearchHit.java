package it.rebyte.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.domain.Platform;

/**
 * Un risultato di {@code GET /search} — proiettato dal documento Meilisearch,
 * mai dal documento Mongo di scrittura (docs/architecture.md §4: la ricerca
 * non legge mai il database di scrittura). {@code sellerSub} non e esposto
 * qui deliberatamente — e un identificatore Cognito interno, non un dato da
 * mostrare pubblicamente; {@code sellerUsername} e il valore da mostrare, ma
 * resta {@code null} finche il piano 02-03 non aggiunge la chiamata Feign a
 * {@code GET /internal/users/{sub}}.
 */
public record SearchHit(
        String id,
        String title,
        String description,
        BigDecimal price,
        ListingType listingType,
        ListingStatus status,
        String categoryId,
        List<String> categoryPath,
        String sellerUsername,
        Instant publishedAt,
        Condition condition,
        String shippingLocation,
        List<Platform> platforms,
        String currentVersion,
        String coverUrl,
        Map<String, Object> attr) {
}
