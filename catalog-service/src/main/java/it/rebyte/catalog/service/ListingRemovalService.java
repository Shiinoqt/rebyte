package it.rebyte.catalog.service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.Moderation;
import it.rebyte.catalog.error.ListingAlreadyRemovedException;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.error.ListingNotOwnedException;
import it.rebyte.catalog.error.ModerationReasonRequiredException;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.repository.ModerationRepository;
import it.rebyte.commons.messaging.RebyteExchanges;

@Service
public class ListingRemovalService {
    private final MongoTemplate mongo;
    private final ModerationRepository moderations;
    private final DomainEventRecorder events;
    private final ListingCache listingCache;
    private final TransactionTemplate tx;

    public ListingRemovalService(MongoTemplate mongo, ModerationRepository moderations, DomainEventRecorder events,
            ListingCache listingCache, PlatformTransactionManager transactionManager) {
        this.mongo = mongo;
        this.moderations = moderations;
        this.events = events;
        this.listingCache = listingCache;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Listing remove(String callerSub, boolean isAdmin, String listingId, String reason) {
        Listing removed = tx.execute(status -> removeInTransaction(callerSub, isAdmin, listingId, reason));
        // An asynchronous event cannot be the only cache invalidation path: a cached ACTIVE response must not survive.
        listingCache.evict(listingId);
        return removed;
    }

    private Listing removeInTransaction(String callerSub, boolean isAdmin, String listingId, String reason) {
        Listing listing = mongo.findById(listingId, Listing.class);
        if (listing == null) throw new ListingNotFoundException(listingId);
        if (listing.getStatus() == ListingStatus.REMOVED) throw new ListingAlreadyRemovedException(listingId);
        if (!isAdmin && !callerSub.equals(listing.getSellerSub())) throw new ListingNotOwnedException(listingId);
        if (isAdmin) {
            if (reason == null || reason.isBlank()) throw new ModerationReasonRequiredException();
        } else {
            if (reason != null) throw new ModerationReasonRequiredException();
            if (listing.getStatus() != ListingStatus.ACTIVE) throw new ListingNotOwnedException(listingId);
        }

        ListingStatus[] allowedStatuses = isAdmin
                ? new ListingStatus[] {ListingStatus.ACTIVE, ListingStatus.SOLD}
                : new ListingStatus[] {ListingStatus.ACTIVE};
        Listing updated = mongo.findAndModify(
                Query.query(where("id").is(listingId).and("status").in((Object[]) allowedStatuses)),
                new Update().set("status", ListingStatus.REMOVED),
                FindAndModifyOptions.options().returnNew(true), Listing.class);
        if (updated == null) throw new ListingAlreadyRemovedException(listingId);

        Instant occurredAt = Instant.now();
        if (isAdmin) moderations.save(new Moderation(UUID.randomUUID().toString(), listingId, callerSub, occurredAt, reason));
        events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_REMOVED,
                new ListingChangedEvent(listingId, updated.getVersion() == null ? 0 : updated.getVersion(), occurredAt), occurredAt);
        return updated;
    }
}
