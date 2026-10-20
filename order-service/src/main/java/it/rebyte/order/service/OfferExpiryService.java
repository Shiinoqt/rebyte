package it.rebyte.order.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.messaging.OfferHoldReleasedEvent;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OfferRepository;

/**
 * Sweep-triggered hold expiry (03.1-CONTEXT.md D-04, D-06, D-10, D-11),
 * called once per reconciliation cycle by payment-service's
 * PaymentReconciliationService.expireDueOffers() through
 * OfferExpiryInternalController.
 */
@Service
public class OfferExpiryService {

    private final OfferRepository repository;
    private final DomainEventRecorder events;
    private final TransactionTemplate transactions;
    private final int batchSize;
    private final Duration acceptClaimTimeout;

    public OfferExpiryService(OfferRepository repository, DomainEventRecorder events,
            PlatformTransactionManager transactionManager,
            @Value("${rebyte.offers.expiry-batch-size:100}") int batchSize,
            @Value("${rebyte.offers.accept-claim-timeout:PT2M}") Duration acceptClaimTimeout) {
        this.repository = repository;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.acceptClaimTimeout = acceptClaimTimeout;
    }

    public int expireDue() {
        Instant now = Instant.now();
        Pageable batch = PageRequest.of(0, batchSize);
        int expiredCount = 0;
        for (Offer due : repository.findDueHolds(now, batch)) {
            if (Boolean.TRUE.equals(transactions.execute(status -> expireOneHold(due, now)))) {
                expiredCount++;
            }
        }
        for (Offer stale : repository.findStaleClaims(now.minus(acceptClaimTimeout), batch)) {
            transactions.executeWithoutResult(status -> recoverStaleClaim(stale));
        }
        return expiredCount;
    }

    private boolean expireOneHold(Offer due, Instant now) {
        if (repository.expireDueHold(due.getId(), now) != 1) {
            return false;
        }
        Offer expired = repository.findById(due.getId()).orElseThrow();
        events.record(RebyteExchanges.OFFER_EXPIRED, expired);
        events.record(RebyteExchanges.OFFER_HOLD_RELEASED, expired.getId(),
                OfferHoldReleasedEvent.of(expired, expired.getHoldReservationId()));
        return true;
    }

    /**
     * D-06: compensates an accept attempt whose outcome can no longer be
     * observed (the service crashed between claiming and clearing). Not
     * counted in expiredCount — the offer stays PENDING.
     */
    private void recoverStaleClaim(Offer stale) {
        String holdReservationId = stale.getHoldReservationId();
        if (repository.clearClaim(stale.getId(), holdReservationId) == 1) {
            events.record(RebyteExchanges.OFFER_HOLD_RELEASED, stale.getId(),
                    OfferHoldReleasedEvent.of(stale, holdReservationId));
        }
    }
}
