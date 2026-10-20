package it.rebyte.catalog.dto;

import java.util.List;

/** Category and ancestor chain needed for a listing breadcrumb. */
public record ListingCategoryView(String id, String name, List<String> ancestors) {
    public ListingCategoryView { ancestors = List.copyOf(ancestors); }
}
