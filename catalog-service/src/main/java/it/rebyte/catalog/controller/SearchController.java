package it.rebyte.catalog.controller;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.dto.SearchRequest;
import it.rebyte.catalog.dto.SearchRequest.AttributeFilter;
import it.rebyte.catalog.dto.SearchResultPage;
import it.rebyte.catalog.error.AttributeFilterNotAllowedException;
import it.rebyte.catalog.search.ListingSearchService;

/** Public filtered search and category browsing endpoints. */
@RestController
public class SearchController {

    private static final String ATTRIBUTE_PREFIX = "attr.";

    private final ListingSearchService listingSearchService;

    public SearchController(ListingSearchService listingSearchService) {
        this.listingSearchService = listingSearchService;
    }

    @GetMapping("/search")
    public SearchResultPage search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) ListingType listingType,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "0") int size,
            @RequestParam MultiValueMap<String, String> parameters) {
        return listingSearchService.search(request(
                q, categoryId, listingType, minPrice, maxPrice, offset, size, parameters));
    }

    @GetMapping("/categories/{categoryId}/listings")
    public SearchResultPage browseCategory(
            @PathVariable String categoryId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "0") int size,
            @RequestParam MultiValueMap<String, String> parameters) {
        return listingSearchService.search(request(
                null, categoryId, null, null, null, offset, size, parameters));
    }

    private SearchRequest request(String query, String categoryId, ListingType listingType,
            BigDecimal minimumPrice, BigDecimal maximumPrice, int offset, int size,
            MultiValueMap<String, String> parameters) {
        Map<String, MutableAttributeFilter> mutable = new LinkedHashMap<>();
        parameters.forEach((key, values) -> {
            if (!key.startsWith(ATTRIBUTE_PREFIX) || values.isEmpty()) {
                return;
            }
            String suffix = key.substring(ATTRIBUTE_PREFIX.length());
            String name = suffix.endsWith(".min") || suffix.endsWith(".max")
                    ? suffix.substring(0, suffix.lastIndexOf('.'))
                    : suffix;
            MutableAttributeFilter filter = mutable.computeIfAbsent(name, ignored -> new MutableAttributeFilter());
            try {
                if (suffix.endsWith(".min")) {
                    filter.minimum = new BigDecimal(values.getFirst());
                } else if (suffix.endsWith(".max")) {
                    filter.maximum = new BigDecimal(values.getFirst());
                } else {
                    filter.value = values.getFirst();
                }
            } catch (NumberFormatException e) {
                throw new AttributeFilterNotAllowedException("attribute '" + name + "' requires a numeric bound");
            }
        });
        Map<String, AttributeFilter> attributes = new LinkedHashMap<>();
        mutable.forEach((name, filter) -> {
            if (filter.value != null && (filter.minimum != null || filter.maximum != null)) {
                throw new AttributeFilterNotAllowedException(
                        "attribute '" + name + "' cannot combine equality and range filters");
            }
            attributes.put(name, new AttributeFilter(filter.value, filter.minimum, filter.maximum));
        });
        return new SearchRequest(query, categoryId, listingType, minimumPrice, maximumPrice,
                attributes, offset, size);
    }

    private static final class MutableAttributeFilter {
        private String value;
        private BigDecimal minimum;
        private BigDecimal maximum;
    }
}
