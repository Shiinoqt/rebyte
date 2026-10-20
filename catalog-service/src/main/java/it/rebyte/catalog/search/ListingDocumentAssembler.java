package it.rebyte.catalog.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.service.EffectiveSchemaResolver;

/**
 * Builds a {@link ListingDocument} from a {@link Listing}, denormalizing
 * {@code categoryPath} from the listing's category and its ancestor chain
 * (docs/architecture.md §4) — this is why the assembler needs {@link
 * CategoryRepository}, not just the listing itself.
 *
 * <p>Also projects {@link Listing#getAttributes()} into the reserved {@code
 * attr} namespace, resolved through {@link EffectiveSchemaResolver} —
 * unlike the write path ({@code ListingPublishService}), this is a
 * non-transactional index-time READ, so the Redis-cached whole tree is the
 * right source: a schema change lagging by the same few seconds the search
 * index itself lags by is an acceptable, already-accepted property of this
 * CQRS projection, not a correctness gap the write path has.
 */
@Component
public class ListingDocumentAssembler {

    private final CategoryRepository categoryRepository;
    private final EffectiveSchemaResolver effectiveSchemaResolver;

    public ListingDocumentAssembler(CategoryRepository categoryRepository, EffectiveSchemaResolver effectiveSchemaResolver) {
        this.categoryRepository = categoryRepository;
        this.effectiveSchemaResolver = effectiveSchemaResolver;
    }

    public ListingDocument assemble(Listing listing, long seq) {
        Category category = categoryRepository.findById(listing.getCategoryId())
                .orElseThrow(() -> new IllegalStateException(
                        "category " + listing.getCategoryId() + " not found while indexing listing " + listing.getId()));

        List<String> categoryPath = new ArrayList<>(category.getAncestorNames());
        categoryPath.add(category.getName());
        List<String> categoryPathIds = categoryPathIds(category);

        HardwareListing hardware = listing.getHardware();
        SoftwareListing software = listing.getSoftware();

        return new ListingDocument(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPrice(),
                listing.type(),
                listing.getStatus(),
                listing.getCategoryId(),
                categoryPathIds,
                categoryPath,
                listing.getSellerSub(),
                // Identity-service data is not resolved while building the local search projection.
                null,
                listing.getPublication(),
                hardware != null ? hardware.getCondition() : null,
                hardware != null ? hardware.getShippingLocation() : null,
                software == null ? List.of() : List.copyOf(software.getPlatforms()),
                software == null ? null : software.currentVersion().getNumber(),
                listing.getPhotos().isEmpty() ? null : listing.getPhotos().getFirst(),
                seq,
                projectAttributes(listing));
    }

    private List<String> categoryPathIds(Category category) {
        List<String> reversed = new ArrayList<>();
        Category current = category;
        while (current != null) {
            reversed.add(current.getId());
            String parentId = current.getParentId();
            current = parentId == null ? null : categoryRepository.findById(parentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "category " + parentId + " not found while building category path"));
        }
        return List.copyOf(reversed.reversed());
    }

    /**
     * Each stored value is written under its definition's normalised name,
     * resolved through the leaf's effective schema — never under the raw
     * definition id, which is meaningless to a search filter. A stored
     * value whose definition no longer resolves in the effective schema
     * (the category tree changed since this listing was written) is
     * skipped rather than surfaced under a stale or absent name; it stays
     * on the listing document itself, only absent from this projection.
     */
    private Map<String, Object> projectAttributes(Listing listing) {
        if (listing.getAttributes().isEmpty()) {
            return Map.of();
        }
        List<AttributeDefinition> effectiveSchema = effectiveSchemaResolver.effectiveSchema(listing.getCategoryId());
        Map<String, String> nameById = new LinkedHashMap<>();
        for (AttributeDefinition definition : effectiveSchema) {
            nameById.put(definition.getId(), definition.getName());
        }

        Map<String, Object> attr = new LinkedHashMap<>();
        for (Map.Entry<String, AttributeValue> entry : listing.getAttributes().entrySet()) {
            String name = nameById.get(entry.getKey());
            if (name != null) {
                attr.put(name, entry.getValue().anyValue());
            }
        }
        return attr;
    }
}
