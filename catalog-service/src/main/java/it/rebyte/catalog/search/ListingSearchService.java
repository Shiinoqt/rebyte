package it.rebyte.catalog.search;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.SearchResult;
import com.meilisearch.sdk.model.Searchable;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.dto.SearchHit;
import it.rebyte.catalog.dto.SearchRequest;
import it.rebyte.catalog.dto.SearchRequest.AttributeFilter;
import it.rebyte.catalog.dto.SearchResultPage;
import it.rebyte.catalog.error.AttributeFilterNotAllowedException;
import it.rebyte.catalog.error.CategoryNotFoundException;
import it.rebyte.catalog.error.InvalidPriceException;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.search.SearchFilterBuilder.ResolvedAttributeFilter;
import it.rebyte.catalog.service.EffectiveSchemaResolver;
import it.rebyte.catalog.service.PhotoUrls;

/** Executes all public searches against the Meilisearch read model. */
@Service
public class ListingSearchService {

    private final Index listingsIndex;
    private final ObjectMapper objectMapper;
    private final CategoryRepository categoryRepository;
    private final EffectiveSchemaResolver schemaResolver;
    private final ListingFacetService facetService;
    private final PhotoUrls photoUrls;
    private final int defaultSize;
    private final int maximumSize;
    private final int priceScale;

    public ListingSearchService(Index listingsIndex, ObjectMapper objectMapper,
            CategoryRepository categoryRepository, EffectiveSchemaResolver schemaResolver,
            ListingFacetService facetService, PhotoUrls photoUrls,
            @Value("${rebyte.search.page.default-size:20}") int defaultSize,
            @Value("${rebyte.search.page.maximum-size:100}") int maximumSize,
            @Value("${rebyte.catalog.price.scale:2}") int priceScale) {
        this.listingsIndex = listingsIndex;
        this.objectMapper = objectMapper;
        this.categoryRepository = categoryRepository;
        this.schemaResolver = schemaResolver;
        this.facetService = facetService;
        this.photoUrls = photoUrls;
        this.defaultSize = defaultSize;
        this.maximumSize = maximumSize;
        this.priceScale = priceScale;
    }

    /** Compatibility entry point retained for the free-text search contract. */
    public SearchResultPage search(String query) {
        return search(new SearchRequest(query, null, null, null, null, Map.of(), 0, defaultSize));
    }

    public SearchResultPage search(SearchRequest request) {
        SearchContext context = context(request);
        int offset = Math.max(0, request.offset());
        int requestedSize = request.size() <= 0 ? defaultSize : request.size();
        int size = Math.min(requestedSize, maximumSize);

        com.meilisearch.sdk.SearchRequest.SearchRequestBuilder builder = com.meilisearch.sdk.SearchRequest.builder()
                .filter(SearchFilterBuilder.build(context.categoryId(), request.listingType(),
                        priceBound(request.minimumPrice()), priceBound(request.maximumPrice()), context.attributes()))
                .offset(offset)
                .limit(size);
        if (StringUtils.hasText(request.query())) {
            builder.q(request.query());
        }
        if (!context.schema().isEmpty()) {
            builder.facets(facetService.fields(context.schema()));
        }

        try {
            Searchable searchable = listingsIndex.search(builder.build());
            List<SearchHit> hits = searchable.getHits().stream().map(this::toHit).toList();
            if (searchable instanceof SearchResult result) {
                return new SearchResultPage(hits, result.getEstimatedTotalHits(), offset, size,
                        facetService.distributions(context.schema(), result.getFacetDistribution()));
            }
            return new SearchResultPage(hits, hits.size(), offset, size, List.of());
        } catch (MeilisearchException e) {
            throw new IllegalStateException("search failed", e);
        }
    }

    private SearchContext context(SearchRequest request) {
        if (request.categoryId() == null) {
            if (!request.attributes().isEmpty()) {
                throw new AttributeFilterNotAllowedException("attribute filters require a selected leaf category");
            }
            return new SearchContext(null, List.of(), List.of());
        }

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(request.categoryId()));
        List<AttributeDefinition> schema = category.leaf()
                ? schemaResolver.effectiveSchema(category.getId())
                : List.of();
        if (!request.attributes().isEmpty() && !category.leaf()) {
            throw new AttributeFilterNotAllowedException("attribute filters require a selected leaf category");
        }
        return new SearchContext(category.getId(), schema, resolveAttributes(request.attributes(), schema));
    }

    private List<ResolvedAttributeFilter> resolveAttributes(
            Map<String, AttributeFilter> requested, List<AttributeDefinition> schema) {
        Map<String, AttributeDefinition> definitions = new LinkedHashMap<>();
        schema.forEach(definition -> definitions.put(definition.getName(), definition));
        List<ResolvedAttributeFilter> resolved = new ArrayList<>();
        requested.forEach((name, filter) -> {
            AttributeDefinition definition = definitions.get(name);
            if (definition == null) {
                throw new AttributeFilterNotAllowedException("attribute '" + name + "' is not in the selected schema");
            }
            resolved.add(new ResolvedAttributeFilter(name, definition.getType(), validateFilter(definition, filter)));
        });
        return List.copyOf(resolved);
    }

    private AttributeFilter validateFilter(AttributeDefinition definition, AttributeFilter filter) {
        if (definition.getType() == AttributeType.ENUM || definition.getType() == AttributeType.BOOLEAN) {
            if (filter.value() == null) {
                throw new AttributeFilterNotAllowedException(
                        "attribute '" + definition.getName() + "' only supports equality filtering");
            }
            if (definition.getType() == AttributeType.BOOLEAN
                    && !filter.value().equalsIgnoreCase("true")
                    && !filter.value().equalsIgnoreCase("false")) {
                throw new AttributeFilterNotAllowedException(
                        "attribute '" + definition.getName() + "' requires true or false");
            }
            return filter;
        }
        if (filter.value() != null) {
            try {
                BigDecimal exact = new BigDecimal(filter.value());
                return new AttributeFilter(null, exact, exact);
            } catch (NumberFormatException e) {
                throw new AttributeFilterNotAllowedException(
                        "attribute '" + definition.getName() + "' requires a numeric value");
            }
        }
        SearchFilterBuilder.range("attr." + definition.getName(), filter.minimum(), filter.maximum());
        return filter;
    }

    private SearchHit toHit(HashMap<String, Object> hit) {
        Object coverPhotoKey = hit.remove("coverPhotoKey");
        SearchHit converted = objectMapper.convertValue(hit, SearchHit.class);
        String coverUrl = coverPhotoKey instanceof String key ? photoUrls.toUrl(key) : null;
        return new SearchHit(converted.id(), converted.title(), converted.description(), converted.price(),
                converted.listingType(), converted.status(), converted.categoryId(), converted.categoryPath(),
                converted.sellerUsername(), converted.publishedAt(), converted.condition(),
                converted.shippingLocation(), converted.platforms(), converted.currentVersion(), coverUrl,
                converted.attr());
    }

    private BigDecimal priceBound(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.setScale(priceScale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidPriceException(priceScale);
        }
    }

    private record SearchContext(String categoryId, List<AttributeDefinition> schema,
            List<ResolvedAttributeFilter> attributes) {
    }
}
