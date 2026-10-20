package it.rebyte.catalog.service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mongodb.MongoException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.catalog.domain.Favourite;
import it.rebyte.catalog.domain.FavouriteId;
import it.rebyte.catalog.domain.FavouriteTransitionClock;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.error.FavouriteNotFoundException;
import it.rebyte.catalog.error.ListingNotAvailableException;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.error.SelfFavouriteException;
import it.rebyte.catalog.messaging.FavouriteChangedEvent;
import it.rebyte.catalog.dto.FavouriteEntry;
import it.rebyte.catalog.dto.FavouriteListingStatus;
import it.rebyte.catalog.dto.FavouritePage;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.repository.FavouriteRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.commons.messaging.RebyteExchanges;

@Service
public class FavouriteService {
    private static final Logger log = LoggerFactory.getLogger(FavouriteService.class);

    private final FavouriteRepository favourites;
    private final ListingRepository listings;
    private final DomainEventRecorder events;
    private final MongoTemplate mongo;
    private final TransactionTemplate tx;
    private final PhotoUrls photoUrls;

    public FavouriteService(FavouriteRepository f, ListingRepository l, DomainEventRecorder e,
            MongoTemplate mongo, PlatformTransactionManager tm, PhotoUrls photoUrls) {
        favourites = f;
        listings = l;
        events = e;
        this.mongo = mongo;
        this.photoUrls = photoUrls;
        tx = new TransactionTemplate(tm);
    }

    public Favourite add(String user, String listingId) {
        return retryTransient(() -> addOnce(user, listingId));
    }

    private Favourite addOnce(String user, String listingId) {
        FavouriteId id = new FavouriteId(user, listingId);
        try {
            return tx.execute(s -> {
                Listing l = listings.findById(listingId)
                        .orElseThrow(() -> new ListingNotFoundException(listingId));
                if (l.getStatus() != ListingStatus.ACTIVE) throw new ListingNotAvailableException(listingId);
                if (user.equals(l.getSellerSub())) throw new SelfFavouriteException();
                Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                String eventId = UUID.randomUUID().toString();
                Favourite saved = mongo.insert(new Favourite(id, createdAt, l.getSellerSub()));
                long sequence = nextSequence(id);
                events.record(eventId, RebyteExchanges.LISTING_FAVOURITED,
                        new FavouriteChangedEvent(eventId, user, listingId, l.getSellerSub(), l.getTitle(),
                                createdAt, sequence), createdAt);
                return saved;
            });
        } catch (DuplicateKeyException ex) {
            Favourite existing = favourites.findById(id).orElse(null);
            if (existing != null) return existing;
            log.error("Favourite insert conflicted but no row exists for userSub={} listingId={}", user, listingId, ex);
            throw ex;
        }
    }

    public void remove(String user, String listingId) {
        retryTransient(() -> {
            removeOnce(user, listingId);
            return null;
        });
    }

    private <T> T retryTransient(Supplier<T> operation) {
        for (int attempt = 0; ; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException failure) {
                if (attempt >= 9 || !isTransientTransactionError(failure)) throw failure;
                // A conflicting transaction may still hold the pair's writes while committing.
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(5, 41));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while changing favourite", interrupted);
                }
            }
        }
    }

    private void removeOnce(String user, String listingId) {
        tx.execute(s -> {
            FavouriteId id = new FavouriteId(user, listingId);
            Favourite existing = mongo.findAndRemove(Query.query(Criteria.where("_id").is(id)), Favourite.class);
            if (existing == null) throw new FavouriteNotFoundException(listingId);
            Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            String eventId = UUID.randomUUID().toString();
            String listingTitle = listings.findById(listingId).map(Listing::getTitle).orElse(null);
            long sequence = nextSequence(id);
            events.record(eventId, RebyteExchanges.LISTING_UNFAVOURITED,
                    new FavouriteChangedEvent(eventId, user, listingId, existing.getSellerSub(), listingTitle,
                            occurredAt, sequence), occurredAt);
            return null;
        });
    }

    private long nextSequence(FavouriteId id) {
        FavouriteTransitionClock clock = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)),
                new Update().inc("sequence", 1L), FindAndModifyOptions.options().upsert(true).returnNew(true),
                FavouriteTransitionClock.class);
        return clock.getSequence();
    }

    public FavouritePage list(String user, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        Page<Favourite> favouritesPage = favourites.findByIdUserSub(user, PageRequest.of(safePage, safeSize));
        var listingIds = favouritesPage.getContent().stream().map(favourite -> favourite.getId().listingId()).toList();
        var listingsById = listings.findAllById(listingIds).stream()
                .collect(java.util.stream.Collectors.toMap(Listing::getId, listing -> listing));
        List<FavouriteEntry> entries = favouritesPage.getContent().stream()
                .map(favourite -> entry(favourite, listingsById.get(favourite.getId().listingId())))
                .toList();
        return new FavouritePage(entries, favouritesPage.getNumber(), favouritesPage.getSize(),
                favouritesPage.getTotalElements(), favouritesPage.getTotalPages());
    }

    private FavouriteEntry entry(Favourite favourite, Listing listing) {
        if (listing == null) {
            return new FavouriteEntry(favourite.getId().listingId(), "Removed listing", null, List.of(), null,
                    FavouriteListingStatus.UNAVAILABLE, favourite.getCreatedAt());
        }
        FavouriteListingStatus status = switch (listing.getStatus()) {
            case ACTIVE -> FavouriteListingStatus.AVAILABLE;
            case SOLD -> FavouriteListingStatus.SOLD;
            case REMOVED -> FavouriteListingStatus.UNAVAILABLE;
        };
        List<String> photos = photoUrls.toUrls(listing.getPhotos());
        return new FavouriteEntry(listing.getId(), listing.getTitle(), listing.getPrice(), photos,
                photos.isEmpty() ? null : photos.getFirst(), status, favourite.getCreatedAt());
    }

    private static boolean isTransientTransactionError(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof MongoException mongoException
                    && mongoException.hasErrorLabel("TransientTransactionError")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
