package it.rebyte.catalog.dto;

import java.math.BigDecimal;
import java.util.Map;

import it.rebyte.catalog.domain.ListingType;

/** Validated search input after HTTP query parameters have been parsed. */
public record SearchRequest(
        String query,
        String categoryId,
        ListingType listingType,
        BigDecimal minimumPrice,
        BigDecimal maximumPrice,
        Map<String, AttributeFilter> attributes,
        int offset,
        int size) {

    public SearchRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public record AttributeFilter(String value, BigDecimal minimum, BigDecimal maximum) {
        public AttributeFilter {
            if (value != null && (minimum != null || maximum != null)) {
                throw new IllegalArgumentException("attribute filter cannot combine equality and range");
            }
            if (value == null && minimum == null && maximum == null) {
                throw new IllegalArgumentException("attribute filter requires a value or range");
            }
        }
    }
}
