package it.rebyte.order.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.client.CatalogInternalClient;
import it.rebyte.order.client.FeignErrors;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.domain.OfferStatus;
import it.rebyte.order.domain.RejectionReason;
import it.rebyte.order.dto.CatalogListingResponse;
import it.rebyte.order.dto.OfferView;
import it.rebyte.order.dto.ReserveListingRequest;
import it.rebyte.order.dto.ReserveListingResponse;
import it.rebyte.order.error.OfferNotFoundException;
import it.rebyte.order.error.OfferNotPendingException;
import it.rebyte.order.error.OfferAcceptInProgressException;
import it.rebyte.order.error.OfferAcceptConflictException;
import it.rebyte.order.error.OfferAcceptUnavailableException;
import it.rebyte.order.error.OfferPriceChangedException;
import it.rebyte.order.error.ListingUnavailableException;
import it.rebyte.order.messaging.OfferHoldReleasedEvent;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OfferRepository;

@Service
public class OfferAcceptanceService {
    private static final Logger log = LoggerFactory.getLogger(OfferAcceptanceService.class);
    private final OfferRepository repository;
    private final DomainEventRecorder events;
    private final OfferRejectionService rejections;
    private final CatalogInternalClient catalogClient;
    private final TransactionTemplate transactions;
    private final Duration holdDuration;

    public OfferAcceptanceService(OfferRepository repository, DomainEventRecorder events,
            OfferRejectionService rejections,
            CatalogInternalClient catalogClient, PlatformTransactionManager transactionManager,
            @Value("${rebyte.offers.hold-duration:PT24H}") Duration holdDuration) {
        this.repository = repository;
        this.events = events;
        this.rejections = rejections;
        this.catalogClient = catalogClient;
        this.transactions = new TransactionTemplate(transactionManager);
        this.holdDuration = holdDuration;
    }

    public OfferView accept(String offerId, String callerSub) {
        Offer offer = repository.findById(offerId).orElseThrow(() -> new OfferNotFoundException(offerId));
        if (!offer.getSellerSub().equals(callerSub)) {
            throw new OfferNotFoundException(offerId);
        }
        if (offer.getStatus() != OfferStatus.PENDING) {
            throw new OfferNotPendingException(offerId);
        }
        if (offer.getHoldReservationId() != null) {
            throw new OfferAcceptInProgressException(offerId);
        }
        CatalogListingResponse listing;
        try {
            listing = catalogClient.availability(offer.getListingId());
        } catch (RuntimeException failure) {
            if (!FeignErrors.isNotFound(failure)) {
                throw failure;
            }
            rejectUnavailable(offer);
            throw new ListingUnavailableException(offer.getListingId());
        }
        if (!listing.available()) {
            rejectUnavailable(offer);
            throw new ListingUnavailableException(offer.getListingId());
        }
        if (listing.price().compareTo(offer.getAmount()) <= 0) {
            rejections.reject(offerId, RejectionReason.PRICE_CHANGED);
            throw new OfferPriceChangedException(offerId);
        }
        String holdReservationId = UUID.randomUUID().toString();
        int claimed = transactions.execute(status -> repository.claimAccept(offerId, holdReservationId, Instant.now()));
        if (claimed != 1) {
            Offer current = repository.findById(offerId).orElseThrow(() -> new OfferNotFoundException(offerId));
            if (current.getStatus() != OfferStatus.PENDING) {
                throw new OfferNotPendingException(offerId);
            }
            throw new OfferAcceptInProgressException(offerId);
        }
        ReserveListingRequest request = new ReserveListingRequest(holdReservationId, listing.version());
        ReserveListingResponse reserved;
        try {
            reserved = catalogClient.reserve(offer.getListingId(), request);
        } catch (RuntimeException firstFailure) {
            log.warn("Catalog reserve failed for offer {}; retrying once", offerId, firstFailure);
            try {
                reserved = catalogClient.reserve(offer.getListingId(), request);
            } catch (RuntimeException secondFailure) {
                transactions.executeWithoutResult(status -> {
                    repository.clearClaim(offerId, holdReservationId);
                    recordRelease(offer, holdReservationId);
                });
                throw new OfferAcceptUnavailableException(offerId, secondFailure);
            }
        }
        switch (reserved.outcome()) {
            case ALREADY_TAKEN, NOT_FOUND -> {
                rejectUnavailable(offer);
                throw new ListingUnavailableException(offer.getListingId());
            }
            case NOT_RESERVABLE -> {
                transactions.executeWithoutResult(status -> repository.clearClaim(offerId, holdReservationId));
                throw new OfferAcceptConflictException(offerId);
            }
            case RESERVED -> { }
        }
        OfferView accepted = transactions.execute(status -> {
            int changed = repository.markAccepted(offerId, holdReservationId, Instant.now().plus(holdDuration));
            if (changed != 1) {
                recordRelease(offer, holdReservationId);
                return null;
            }
            for (Offer sibling : repository.findPendingSiblingsForUpdate(offer.getListingId(), offerId)) {
                if (repository.rejectPending(sibling.getId(), RejectionReason.OTHER_ACCEPTED) == 1) {
                    events.record(RebyteExchanges.OFFER_REJECTED, repository.findById(sibling.getId()).orElseThrow());
                }
            }
            Offer winner = repository.findById(offerId).orElseThrow();
            events.record(RebyteExchanges.OFFER_ACCEPTED, winner);
            return OfferView.from(winner);
        });
        if (accepted == null) {
            throw new OfferNotPendingException(offerId);
        }
        return accepted;
    }

    private void rejectUnavailable(Offer offer) {
        rejections.reject(offer.getId(), RejectionReason.LISTING_UNAVAILABLE);
        rejections.rejectForUnavailableListing(offer.getListingId(), offer.getId());
    }

    private void recordRelease(Offer offer, String holdReservationId) {
        events.record(RebyteExchanges.OFFER_HOLD_RELEASED, offer.getId(),
                OfferHoldReleasedEvent.of(offer, holdReservationId));
    }
}
