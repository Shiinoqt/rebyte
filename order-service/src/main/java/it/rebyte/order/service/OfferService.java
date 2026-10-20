package it.rebyte.order.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.client.CatalogInternalClient;
import it.rebyte.order.client.FeignErrors;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.dto.CatalogListingResponse;
import it.rebyte.order.dto.CreateOfferRequest;
import it.rebyte.order.dto.OfferView;
import it.rebyte.order.error.ListingUnavailableException;
import it.rebyte.order.error.InvalidAmountScaleException;
import it.rebyte.order.error.InvalidOfferAmountException;
import it.rebyte.order.error.OfferHardwareOnlyException;
import it.rebyte.order.error.OfferNotFoundException;
import it.rebyte.order.error.OfferNotPendingException;
import it.rebyte.order.error.SelfOfferException;
import it.rebyte.order.error.DuplicatePendingOfferException;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OfferRepository;

@Service
public class OfferService {
    private final OfferRepository repository;
    private final DomainEventRecorder events;
    private final CatalogInternalClient catalogClient;
    private final TransactionTemplate transactions;

    public OfferService(OfferRepository repository, DomainEventRecorder events, CatalogInternalClient catalogClient,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.events = events;
        this.catalogClient = catalogClient;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public OfferView create(String buyerSub, CreateOfferRequest request) {
        if (request.amount().scale() > 2) {
            throw new InvalidAmountScaleException();
        }
        if (request.amount().signum() <= 0) {
            throw new InvalidOfferAmountException();
        }
        CatalogListingResponse listing;
        try {
            listing = catalogClient.availability(request.listingId());
        } catch (RuntimeException failure) {
            if (FeignErrors.isNotFound(failure)) {
                throw new ListingUnavailableException(request.listingId());
            }
            throw failure;
        }
        if (!listing.available()) {
            throw new ListingUnavailableException(request.listingId());
        }
        if (listing.type() != ListingType.HARDWARE) {
            throw new OfferHardwareOnlyException();
        }
        if (buyerSub.equals(listing.sellerSub())) {
            throw new SelfOfferException();
        }
        if (listing.price().scale() > 2) {
            throw new InvalidAmountScaleException();
        }
        if (request.amount().compareTo(listing.price()) >= 0) {
            throw new InvalidOfferAmountException();
        }
        Offer offer = new Offer(UUID.randomUUID().toString(), listing.id(), buyerSub, listing.sellerSub(),
                listing.listingTitle(), listing.price(), request.amount(), Instant.now());
        Offer saved;
        try {
            saved = transactions.execute(status -> {
                Offer inserted = repository.saveAndFlush(offer);
                events.record(RebyteExchanges.OFFER_CREATED, inserted);
                return inserted;
            });
        } catch (DataIntegrityViolationException conflict) {
            Throwable cause = conflict.getMostSpecificCause();
            if (cause.getMessage() != null && cause.getMessage().contains("uk_offer_buyer_pending_guard")) {
                throw new DuplicatePendingOfferException();
            }
            throw conflict;
        }
        return OfferView.from(saved);
    }

    /** OFR-03/D-09: only the buyer of record may withdraw, and only while the offer is still PENDING. */
    public OfferView withdraw(String offerId, String callerSub) {
        Offer offer = repository.findById(offerId).orElseThrow(() -> new OfferNotFoundException(offerId));
        if (!offer.getBuyerSub().equals(callerSub)) {
            throw new OfferNotFoundException(offerId);
        }
        transactions.executeWithoutResult(status -> {
            if (repository.withdrawPending(offerId) != 1) {
                throw new OfferNotPendingException(offerId);
            }
            events.record(RebyteExchanges.OFFER_WITHDRAWN, repository.findById(offerId).orElseThrow());
        });
        return OfferView.from(repository.findById(offerId).orElseThrow());
    }
}
