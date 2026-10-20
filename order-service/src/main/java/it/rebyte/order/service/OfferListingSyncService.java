package it.rebyte.order.service;

import org.springframework.stereotype.Service;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.client.CatalogInternalClient;
import it.rebyte.order.client.FeignErrors;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.domain.OfferStatus;
import it.rebyte.order.domain.RejectionReason;
import it.rebyte.order.dto.CatalogListingResponse;
import it.rebyte.order.repository.OfferRepository;

/**
 * Keeps pending offers consistent with the listing's current availability and price, and
 * expires an unconverted accepted hold when the listing is removed (03.1-CONTEXT.md D-01, D-02,
 * D-05). The consumer never trusts the event payload's price or status; every decision re-reads
 * the current listing state, and every guarded update is a no-op on redelivery.
 */
@Service
public class OfferListingSyncService {

    private final OfferRepository repository;
    private final OfferRejectionService rejections;
    private final OfferHoldService holds;
    private final CatalogInternalClient catalogClient;

    public OfferListingSyncService(OfferRepository repository, OfferRejectionService rejections,
            OfferHoldService holds, CatalogInternalClient catalogClient) {
        this.repository = repository;
        this.rejections = rejections;
        this.holds = holds;
        this.catalogClient = catalogClient;
    }

    public void onListingChanged(String routingKey, String listingId) {
        if (RebyteExchanges.LISTING_REMOVED.equals(routingKey)) {
            onRemoved(listingId);
        } else {
            onUpdated(listingId);
        }
    }

    /** D-05: REMOVED is terminal — every PENDING offer rejects and every unlinked ACCEPTED hold expires without release. */
    private void onRemoved(String listingId) {
        for (Offer offer : repository.findPendingByListing(listingId)) {
            rejections.reject(offer.getId(), RejectionReason.LISTING_UNAVAILABLE);
        }
        for (Offer offer : repository.findUnlinkedAcceptedByListing(listingId)) {
            holds.expireLostHold(offer);
        }
    }

    /** D-01/D-02: no PENDING offer means nothing to protect, so the catalog is never called. */
    private void onUpdated(String listingId) {
        if (!repository.existsByListingIdAndStatus(listingId, OfferStatus.PENDING)) {
            return;
        }
        CatalogListingResponse listing;
        try {
            listing = catalogClient.availability(listingId);
        } catch (RuntimeException failure) {
            if (!FeignErrors.isNotFound(failure)) {
                throw failure;
            }
            rejections.rejectForUnavailableListing(listingId, null);
            return;
        }
        if (!listing.available()) {
            rejections.rejectForUnavailableListing(listingId, null);
        } else {
            rejections.rejectAtOrAbovePrice(listingId, listing.price());
        }
    }
}
