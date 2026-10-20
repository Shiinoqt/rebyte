package it.rebyte.catalog.search;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.dto.SearchRequest.AttributeFilter;
import it.rebyte.catalog.error.InvalidPriceRangeException;

/** Builds Meilisearch filters without ever accepting raw filter syntax from a caller. */
public final class SearchFilterBuilder {

    private SearchFilterBuilder() {
    }

    public static String[] build(String categoryId, ListingType listingType,
            BigDecimal minimumPrice, BigDecimal maximumPrice, List<ResolvedAttributeFilter> attributes) {
        List<String> filters = new ArrayList<>();
        filters.add("status = ACTIVE");
        if (categoryId != null) {
            filters.add("categoryPathIds = " + quote(categoryId));
        }
        if (listingType != null) {
            filters.add("listingType = " + listingType.name());
        }
        String price = range("price", minimumPrice, maximumPrice);
        if (!price.isEmpty()) {
            filters.add(price);
        }
        for (ResolvedAttributeFilter attribute : attributes) {
            filters.add(attributeExpression(attribute));
        }
        return filters.toArray(String[]::new);
    }

    static String attributeExpression(ResolvedAttributeFilter resolved) {
        AttributeFilter filter = resolved.filter();
        String field = "attr." + resolved.normalizedName();
        if (filter.value() != null) {
            return field + " = " + (resolved.type() == AttributeType.BOOLEAN
                    ? filter.value().toLowerCase()
                    : quote(filter.value()));
        }
        return range(field, filter.minimum(), filter.maximum());
    }

    public static String range(String field, BigDecimal minimum, BigDecimal maximum) {
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new InvalidPriceRangeException();
        }
        List<String> bounds = new ArrayList<>(2);
        if (minimum != null) {
            bounds.add(field + " >= " + minimum.toPlainString());
        }
        if (maximum != null) {
            bounds.add(field + " <= " + maximum.toPlainString());
        }
        return String.join(" AND ", bounds);
    }

    public static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public record ResolvedAttributeFilter(String normalizedName, AttributeType type, AttributeFilter filter) {
    }
}
