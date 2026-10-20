package it.rebyte.catalog.dto;

import java.util.Map;

/** Counts returned by Meilisearch for one effective-schema attribute. */
public record FacetDistribution(String attribute, Map<String, Long> values) {
    public FacetDistribution {
        values = Map.copyOf(values);
    }
}
