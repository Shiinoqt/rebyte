package it.rebyte.catalog.dto;

import java.util.List;

/** Paginated results and optional leaf-category facet distributions. */
public record SearchResultPage(
        List<SearchHit> hits,
        int total,
        int offset,
        int size,
        List<FacetDistribution> facets) {

    public SearchResultPage {
        hits = List.copyOf(hits);
        facets = facets == null ? List.of() : List.copyOf(facets);
    }

    public SearchResultPage(List<SearchHit> hits, int total) {
        this(hits, total, 0, hits.size(), List.of());
    }
}
