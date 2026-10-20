package it.rebyte.catalog.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.domain.Platform;

/**
 * The shape projected into the {@code listings} Meilisearch index. Changes
 * to this shape require an index rebuild. {@code attr} is the reserved
 * namespace for attribute-definition-derived fields: a nested
 * object keyed by the definition's normalised name, which Meilisearch
 * flattens into dot-path filters (e.g. {@code attr.vram_gb}) — matching the
 * {@code attr.*} patterns {@link ListingIndexSettings} already declares. No
 * other field declared here may begin with {@code attr}, or the wildcard
 * baseline and numeric overrides would apply attribute filter features to
 * something that is not an attribute. {@code ListingDocumentAssemblerTest}
 * pins the complete field-name set.
 *
 * <p>{@code sellerUsername} is nullable because local projection does not
 * resolve identity-service data.
 *
 * <p>The seller rating is not denormalized because no profile-change event
 * exists to keep it current; detail reads resolve reputation separately.
 */
public record ListingDocument(
        String id,
        String title,
        String description,
        BigDecimal price,
        ListingType listingType,
        ListingStatus status,
        String categoryId,
        /** Root-to-leaf category identities used for collision-free subtree filtering. */
        List<String> categoryPathIds,
        List<String> categoryPath,
        String sellerSub,
        String sellerUsername,
        Instant publishedAt,
        Condition condition,
        String shippingLocation,
        List<Platform> platforms,
        String currentVersion,
        /**
         * Bare storage key of the first photo. The CDN URL is built at response time so a
         * domain change needs no reindex, and the key itself is never returned to clients.
         */
        String coverPhotoKey,
        long seq,
        Map<String, Object> attr) {
}
