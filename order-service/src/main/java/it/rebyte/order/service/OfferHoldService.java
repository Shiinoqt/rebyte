package it.rebyte.order.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.domain.OfferStatus;
import it.rebyte.order.domain.Order;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OfferRepository;

/**
 * Offer side of order cancellation (03.1-CONTEXT.md D-08, D-10) and the
 * lost-hold expiry during offer-checkout hold verification (D-06 pattern
 * applied to checkout).
 */
@Service
public class OfferHoldService {

    private final OfferRepository repository;
    private final DomainEventRecorder events;
    private final TransactionTemplate transactions;

    public OfferHoldService(OfferRepository repository, DomainEventRecorder events,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * A definite reserve loss or an unrecoverable retry failure verifying
     * an offer's hold during checkout. No catalog release call — catalog no
     * longer holds the listing under this id.
     */
    public void expireLostHold(Offer offer) {
        transactions.executeWithoutResult(status -> {
            if (repository.expireUnlinkedHold(offer.getId()) == 1) {
                events.record(RebyteExchanges.OFFER_EXPIRED, repository.findById(offer.getId()).orElseThrow());
            }
        });
    }

    /**
     * D-08/D-10 branch on an order cancellation. MUST run inside the
     * caller's own transaction, on the managed {@code order}, right after
     * it is marked cancelled and before its release event is recorded.
     * Mutating {@code order}'s reservationId here relies on the
     * OfferRepository {@code @Modifying} calls' {@code
     * flushAutomatically = true} to persist that change to the database
     * before the SAME call's {@code clearAutomatically = true} detaches the
     * entity from the persistence context — the Order field must be set
     * BEFORE calling {@link OfferRepository#unlinkOrder}, not after.
     */
    public void onOrderCancelled(Order order) {
        if (order.getOfferId() == null) {
            return;
        }
        Offer offer = repository.findByIdForUpdate(order.getOfferId()).orElse(null);
        if (offer == null || offer.getStatus() != OfferStatus.ACCEPTED
                || !order.getId().equals(offer.getOrderId())) {
            return;
        }
        boolean stillInHold = Instant.now().isBefore(offer.getExpiresAt());
        boolean recoverable = switch (order.getCancellationReason()) {
            case BUYER_ABORTED, PAYMENT_UNAVAILABLE, TIMEOUT, PAYMENT_DECLINED -> true;
            case LISTING_UNAVAILABLE -> false;
        };
        if (stillInHold && recoverable) {
            order.setReservationId(null);
            repository.unlinkOrder(offer.getId(), order.getId());
        } else if (repository.expireLinked(offer.getId(), order.getId()) == 1) {
            events.record(RebyteExchanges.OFFER_EXPIRED, repository.findById(offer.getId()).orElseThrow());
        }
    }
}
