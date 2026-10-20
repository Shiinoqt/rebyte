package it.rebyte.catalog.service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PriceChange;
import it.rebyte.catalog.error.InvalidPriceException;
import it.rebyte.catalog.error.ListingNotEditableException;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.error.ListingNotOwnedException;
import it.rebyte.catalog.error.PriceUnchangedException;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * The sole writer of {@link Listing}'s price and append-only price history.
 * Appending the old value immediately before replacing it makes every entry
 * chain to its predecessor by construction.
 */
@Service
public class PriceChangeService {

    private final MongoTemplate mongo;
    private final DomainEventRecorder events;
    private final ListingCache listingCache;
    private final TransactionTemplate transactions;
    private final int priceScale;

    public PriceChangeService(
            MongoTemplate mongo,
            DomainEventRecorder events,
            ListingCache listingCache,
            PlatformTransactionManager transactionManager,
            @Value("${rebyte.catalog.price.scale:2}") int priceScale) {
        this.mongo = mongo;
        this.events = events;
        this.listingCache = listingCache;
        this.transactions = new TransactionTemplate(transactionManager);
        this.priceScale = priceScale;
    }

    public Listing changePrice(String sellerSub, String listingId, BigDecimal newPrice) {
        Listing updated = transactions.execute(ignored -> changePriceInTransaction(sellerSub, listingId, newPrice));
        listingCache.evict(listingId);
        return updated;
    }

    private Listing changePriceInTransaction(String sellerSub, String listingId, BigDecimal newPrice) {
        Listing current = mongo.findById(listingId, Listing.class);
        if (current == null) throw new ListingNotFoundException(listingId);
        if (!current.getSellerSub().equals(sellerSub)) throw new ListingNotOwnedException(listingId);
        if (current.getStatus() != ListingStatus.ACTIVE) throw new ListingNotEditableException(listingId);
        if (newPrice.signum() <= 0 || newPrice.scale() > priceScale) throw new InvalidPriceException(priceScale);
        if (newPrice.compareTo(current.getPrice()) == 0) throw new PriceUnchangedException();

        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        PriceChange change = new PriceChange(occurredAt, current.getPrice(), newPrice);
        Listing updated = mongo.findAndModify(
                Query.query(where("id").is(listingId).and("price").is(current.getPrice())),
                new Update().set("price", newPrice).push("priceHistory", change),
                FindAndModifyOptions.options().returnNew(true), Listing.class);
        if (updated == null) throw new ListingNotEditableException(listingId);

        long sequence = updated.getVersion() == null ? 0L : updated.getVersion();
        events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_UPDATED,
                new ListingChangedEvent(listingId, sequence, occurredAt, current.getPrice(), newPrice,
                        current.getTitle()), occurredAt);
        return updated;
    }
}
