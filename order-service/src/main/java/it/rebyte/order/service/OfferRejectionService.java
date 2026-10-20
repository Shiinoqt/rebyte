package it.rebyte.order.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.domain.RejectionReason;
import it.rebyte.order.dto.OfferView;
import it.rebyte.order.error.OfferNotFoundException;
import it.rebyte.order.error.OfferNotPendingException;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OfferRepository;

@Service
public class OfferRejectionService {
    private final OfferRepository repository;
    private final DomainEventRecorder events;
    private final TransactionTemplate transactions;

    public OfferRejectionService(OfferRepository repository, DomainEventRecorder events,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public boolean reject(String offerId, RejectionReason reason) {
        return Boolean.TRUE.equals(transactions.execute(status -> rejectInTransaction(offerId, reason)));
    }

    /** OFR-03/D-09: only the seller of record may reject, and only while the offer is still PENDING. */
    public OfferView rejectBySeller(String offerId, String callerSub) {
        Offer offer = repository.findById(offerId).orElseThrow(() -> new OfferNotFoundException(offerId));
        if (!offer.getSellerSub().equals(callerSub)) {
            throw new OfferNotFoundException(offerId);
        }
        if (!reject(offerId, RejectionReason.SELLER)) {
            throw new OfferNotPendingException(offerId);
        }
        return OfferView.from(repository.findById(offerId).orElseThrow());
    }

    public int rejectForUnavailableListing(String listingId, String excludedId) {
        return transactions.execute(status -> {
            var pending = repository.findPendingForUpdate(listingId);
            if (repository.existsOtherClaim(listingId, excludedId)) {
                return 0;
            }
            int rejected = 0;
            for (Offer offer : pending) {
                if (rejectInTransaction(offer.getId(), RejectionReason.LISTING_UNAVAILABLE)) {
                    rejected++;
                }
            }
            return rejected;
        });
    }

    /**
     * D-02: an offer amount at or above the listing's current price no longer holds an
     * invariant of "a pending offer is below the current price", claimed or not — catalog
     * refuses a re-price unless the listing is ACTIVE, so an accept racing this re-price fails
     * its reserve on the stale version anyway.
     */
    public int rejectAtOrAbovePrice(String listingId, BigDecimal price) {
        return transactions.execute(status -> {
            var pending = repository.findPendingForUpdate(listingId);
            int rejected = 0;
            for (Offer offer : pending) {
                if (offer.getAmount().compareTo(price) >= 0
                        && rejectInTransaction(offer.getId(), RejectionReason.PRICE_CHANGED)) {
                    rejected++;
                }
            }
            return rejected;
        });
    }

    private boolean rejectInTransaction(String offerId, RejectionReason reason) {
        if (repository.rejectPending(offerId, reason) != 1) {
            return false;
        }
        events.record(RebyteExchanges.OFFER_REJECTED, repository.findById(offerId).orElseThrow());
        return true;
    }
}
