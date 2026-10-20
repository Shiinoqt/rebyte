package it.rebyte.catalog.dto;

import java.time.Instant;
import java.util.List;

import it.rebyte.catalog.domain.Category;

/**
 * Response for {@code POST /categories} and {@code GET /categories/{id}} —
 * never the Mongo document itself. Deliberately omits {@code listingCount}:
 * it is an internal conflict token (T-02-02-06), not something a public
 * response should leak.
 */
public record CategoryResponse(
        String id,
        String name,
        String parentId,
        List<String> ancestorNames,
        List<String> subcategoryIds,
        boolean leaf,
        Instant createdAt) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(), category.getName(), category.getParentId(),
                category.getAncestorNames(), category.getSubcategoryIds(),
                category.leaf(), category.getCreatedAt());
    }
}
