package it.rebyte.catalog.service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.mongodb.MongoException;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.dto.ListingAvailability;
import it.rebyte.catalog.dto.ReserveListingResponse;
import it.rebyte.catalog.dto.ReserveListingResponse.Outcome;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.commons.messaging.RebyteExchanges;

@Service
public class ListingReservationService {
    private static final Logger log = LoggerFactory.getLogger(ListingReservationService.class);
    private final MongoTemplate mongo;
    private final DomainEventRecorder events;
    private final TransactionTemplate transactions;

    public ListingReservationService(MongoTemplate mongo, DomainEventRecorder events,
            PlatformTransactionManager transactionManager) {
        this.mongo = mongo;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Direct Mongo read, never the search projection or Redis: checkout needs the current price. */
    public ListingAvailability availability(String id) {
        Listing listing = getListing(id);
        BigDecimal shippingCost = listing.getHardware() != null
                ? listing.getHardware().getShippingCost()
                : BigDecimal.ZERO.setScale(2);
        long version = listing.getVersion() == null ? 0L : listing.getVersion();
        return new ListingAvailability(id, listing.getSellerSub(), listing.getTitle(), listing.getPrice(),
                shippingCost, listing.type(), listing.getPaymentMethods(),
                listing.getStatus() == ListingStatus.ACTIVE, version,
                listing.getSoftware() == null ? null : listing.getSoftware().effectiveLicenceKeyPattern());
    }

    public Listing getListing(String id) {
        Listing listing = mongo.findById(id, Listing.class);
        if (listing == null) throw new ListingNotFoundException(id);
        return listing;
    }

    /**
     * Idempotent on the caller-supplied {@code reservationId} (D-17): repeating the same id
     * against an already-SOLD listing echoes RESERVED instead of ALREADY_TAKEN, so a retry
     * after an unknown outcome is always safe. {@code expectedVersion} fences a stale read —
     * a price/status edit since the caller's availability read returns NOT_RESERVABLE instead
     * of reserving at the old price. A reservationId already recorded in {@code
     * canceledReservationIds} is fenced the same way: a release that overtook a delayed reserve
     * must never let that reserve mark the listing SOLD.
     */
    public ReserveListingResponse reserve(String id, String reservationId, Long expectedVersion) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reserve requires reservationId");
        }
        return write(() -> {
            Listing updated = mongo.findAndModify(Query.query(where("id").is(id)
                            .and("status").is(ListingStatus.ACTIVE).and("hardware").ne(null)
                            .and("version").is(expectedVersion)
                            .and("canceledReservationIds").ne(reservationId)),
                    new Update().set("status", ListingStatus.SOLD).set("reservationId", reservationId),
                    FindAndModifyOptions.options().returnNew(true), Listing.class);
            if (updated != null) {
                recordChange(updated);
                return new ReserveListingResponse(Outcome.RESERVED, reservationId);
            }
            Listing current = mongo.findById(id, Listing.class);
            if (current == null) return new ReserveListingResponse(Outcome.NOT_FOUND, null);
            if (current.getHardware() == null) return new ReserveListingResponse(Outcome.NOT_RESERVABLE, null);
            if (current.getCanceledReservationIds().contains(reservationId)) {
                return new ReserveListingResponse(Outcome.NOT_RESERVABLE, null);
            }
            if (current.getStatus() == ListingStatus.SOLD) {
                return reservationId.equals(current.getReservationId())
                        ? new ReserveListingResponse(Outcome.RESERVED, reservationId)
                        : new ReserveListingResponse(Outcome.ALREADY_TAKEN, null);
            }
            // ACTIVE, not fenced: the atomic match failed only because expectedVersion is stale.
            return new ReserveListingResponse(Outcome.NOT_RESERVABLE, null);
        });
    }

    /**
     * Only the reservation that produced the event can be released, even after a later sale.
     * The fence write is unconditional: a release that arrives BEFORE its matching reserve (the
     * order was cancelled while the reserve call was still delayed/retrying) must still tombstone
     * {@code reservationId}, or the late reserve below would go on to mark the listing SOLD.
     */
    public boolean release(String id, String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("Release event requires reservationId");
        }
        boolean released = write(() -> {
            Listing updated = mongo.findAndModify(Query.query(where("id").is(id)
                            .and("status").is(ListingStatus.SOLD).and("hardware").ne(null)
                            .and("reservationId").is(reservationId)),
                    new Update().set("status", ListingStatus.ACTIVE).unset("reservationId")
                            .addToSet("canceledReservationIds", reservationId),
                    FindAndModifyOptions.options().returnNew(true), Listing.class);
            if (updated != null) {
                recordChange(updated);
                return true;
            }
            // Fence-only write: the collection-name overload skips Spring Data's automatic
            // @Version increment (only the entity-class overloads apply optimistic locking), so
            // a concurrent buyer's expectedVersion read for THIS listing is not invalidated by a
            // release that never touched its price/status/availability. Query "_id" directly —
            // without an entity class, Spring Data does not translate "id" to the actual "_id"
            // storage key, which would otherwise match nothing.
            mongo.updateFirst(Query.query(where("_id").is(id)),
                    new Update().addToSet("canceledReservationIds", reservationId),
                    mongo.getCollectionName(Listing.class));
            return false;
        });
        log.info("{} listingId={} reservationId={}", released ? "RELEASED" : "RELEASE_IGNORED", id, reservationId);
        return released;
    }

    private void recordChange(Listing listing) {
        Instant now = Instant.now();
        events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_UPDATED,
                new ListingChangedEvent(listing.getId(), listing.getVersion(), now), now);
    }

    private <T> T write(Supplier<T> operation) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transactions.execute(status -> operation.get());
            } catch (RuntimeException failure) {
                if (attempt >= 9 || !isTransient(failure)) throw failure;
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(5, 41));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while updating reservation", interrupted);
                }
            }
        }
    }

    private boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongoFailure
                    && mongoFailure.hasErrorLabel("TransientTransactionError")) return true;
        }
        return false;
    }
}
