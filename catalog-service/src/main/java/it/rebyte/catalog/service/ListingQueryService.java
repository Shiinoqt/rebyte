package it.rebyte.catalog.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import feign.FeignException;
import it.rebyte.catalog.client.UserServiceClient;
import it.rebyte.catalog.client.UserSummary;
import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.dto.ListingAttributeView;
import it.rebyte.catalog.dto.ListingCategoryView;
import it.rebyte.catalog.dto.ListingDetailResponse;
import it.rebyte.catalog.dto.ModerationView;
import it.rebyte.catalog.dto.PriceChangeResponse;
import it.rebyte.catalog.dto.SellerSummaryView;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.repository.ModerationRepository;

/** Presentation read path. Checkout uses ListingReservationService's direct Mongo read instead. */
@Service
public class ListingQueryService {

    private static final Logger log = LoggerFactory.getLogger(ListingQueryService.class);
    private final ListingRepository listings;
    private final CategoryRepository categories;
    private final UserServiceClient users;
    private final ListingCache listingCache;
    private final ModerationRepository moderations;
    private final PhotoUrls photoUrls;

    public ListingQueryService(ListingRepository listings, CategoryRepository categories, UserServiceClient users,
            ListingCache listingCache, ModerationRepository moderations,
            PhotoUrls photoUrls) {
        this.listings = listings;
        this.categories = categories;
        this.users = users;
        this.listingCache = listingCache;
        this.moderations = moderations;
        this.photoUrls = photoUrls;
    }

    public ListingDetailResponse detail(String listingId, String callerSub) {
        Listing listing = listingCache.get(listingId,
                () -> listings.findById(listingId).orElseThrow(() -> new ListingNotFoundException(listingId)));
        ModerationView moderation = null;
        if (listing.getStatus() == ListingStatus.REMOVED) {
            if (callerSub == null || !callerSub.equals(listing.getSellerSub())) {
                throw new ListingNotFoundException(listingId);
            }
            moderation = moderations.findFirstByListingId(listingId)
                    .map(m -> new ModerationView(m.getReason(), m.getInstant())).orElse(null);
        }
        Category category = categories.findById(listing.getCategoryId())
                .orElseThrow(() -> new ListingNotFoundException(listingId));
        UserSummary summary = seller(listing.getSellerSub());
        return ListingDetailResponse.from(listing,
                new ListingCategoryView(category.getId(), category.getName(), category.getAncestorNames()),
                attributeViews(listing, resolveEffectiveSchema(category)),
                listing.getPriceHistory().stream().map(PriceChangeResponse::from).toList(), sellerView(summary),
                photoUrls.toUrls(listing.getPhotos()), moderation);
    }

    private List<AttributeDefinition> resolveEffectiveSchema(Category leaf) {
        List<Category> selfToRoot = new ArrayList<>();
        Category current = leaf;
        while (current != null) {
            selfToRoot.add(current);
            current = current.getParentId() == null ? null : categories.findById(current.getParentId()).orElse(null);
        }
        Collections.reverse(selfToRoot);
        List<AttributeDefinition> result = new ArrayList<>();
        for (Category category : selfToRoot) result.addAll(category.getAttributeDefinitions());
        return result;
    }

    private List<ListingAttributeView> attributeViews(Listing listing, List<AttributeDefinition> schema) {
        Map<String, AttributeDefinition> definitionsById = new LinkedHashMap<>();
        List<ListingAttributeView> result = new ArrayList<>();
        for (AttributeDefinition definition : schema) {
            definitionsById.put(definition.getId(), definition);
            AttributeValue value = listing.getAttributes().get(definition.getId());
            if (value != null) {
                result.add(new ListingAttributeView(definition.getName(), definition.getType(), value.anyValue(), definition.getUnit()));
            }
        }
        for (Map.Entry<String, AttributeValue> entry : listing.getAttributes().entrySet()) {
            if (!definitionsById.containsKey(entry.getKey())) {
                AttributeValue value = entry.getValue();
                result.add(new ListingAttributeView(entry.getKey(), value.getType(), value.anyValue(), null));
            }
        }
        return List.copyOf(result);
    }

    private UserSummary seller(String sellerSub) {
        try {
            return users.getSummary(sellerSub);
        } catch (FeignException | IllegalStateException ex) {
            log.error("seller lookup failed for sellerSub={}", sellerSub, ex);
            return new UserSummary(sellerSub, null, null, false, null, 0);
        }
    }

    private SellerSummaryView sellerView(UserSummary summary) {
        return new SellerSummaryView(summary.sub(), summary.username(), avatarUrl(summary.avatar()),
                summary.ratingAvg(), summary.ratingCount());
    }

    private String avatarUrl(String key) {
        return photoUrls.toUrl(key);
    }
}
