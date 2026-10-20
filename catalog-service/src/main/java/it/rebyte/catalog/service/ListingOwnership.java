package it.rebyte.catalog.service;

import org.springframework.stereotype.Component;

import it.rebyte.catalog.repository.ListingRepository;

/**
 * The {@code @listings.isOwner(...)} bean docs/architecture.md §7c names as
 * the ownership half of {@code @PreAuthorize("hasRole('ADMIN') or
 * @listings.isOwner(#id, authentication.name)")}. {@code
 * JwtAuthenticationToken#getName()} returns the JWT {@code sub} claim by
 * default, matching {@code Listing.sellerSub} — the same identifier
 * {@code ListingPublishService} resolves the seller from.
 *
 * <p>Used by this plan's photo endpoints (Task 2) and by plans 02-04, 02-05
 * and 02-06's listing edit/withdraw/moderation endpoints.
 */
@Component("listings")
public class ListingOwnership {

    private final ListingRepository listingRepository;

    public ListingOwnership(ListingRepository listingRepository) {
        this.listingRepository = listingRepository;
    }

    /**
     * {@code false} for a listing that does not exist — a
     * {@code @PreAuthorize} expression must return a boolean, so a missing
     * listing is treated as "not owned" here; the controller's own lookup
     * is what turns that into a 404 rather than this method throwing.
     */
    public boolean isOwner(String listingId, String sellerSub) {
        return listingRepository.findById(listingId)
                .map(listing -> listing.getSellerSub().equals(sellerSub))
                .orElse(false);
    }
}
