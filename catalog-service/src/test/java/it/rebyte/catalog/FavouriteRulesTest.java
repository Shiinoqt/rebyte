package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import it.rebyte.catalog.domain.Favourite;
import it.rebyte.catalog.domain.FavouriteId;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.error.ListingNotAvailableException;
import it.rebyte.catalog.error.SelfFavouriteException;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.repository.FavouriteRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.service.FavouriteService;
import it.rebyte.catalog.service.PhotoUrls;

@ExtendWith(MockitoExtension.class)
class FavouriteRulesTest {
    @Mock FavouriteRepository favourites;
    @Mock ListingRepository listings;
    @Mock DomainEventRecorder events;
    @Mock MongoTemplate mongo;

    private static final PlatformTransactionManager NOOP = new PlatformTransactionManager() {
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException { return new SimpleTransactionStatus(); }
        public void commit(TransactionStatus status) throws TransactionException { }
        public void rollback(TransactionStatus status) throws TransactionException { }
    };

    @Test void sellerCannotFavouriteOwnListing() {
        when(listings.findById("l1")).thenReturn(Optional.of(listing(ListingStatus.ACTIVE, "seller")));
        assertThatThrownBy(() -> service().add("seller", "l1")).isInstanceOf(SelfFavouriteException.class);
        verify(mongo, never()).insert(any(Favourite.class));
    }

    @Test void unavailableListingCannotBeFavourited() {
        when(listings.findById("l1")).thenReturn(Optional.of(listing(ListingStatus.SOLD, "seller")));
        assertThatThrownBy(() -> service().add("buyer", "l1")).isInstanceOf(ListingNotAvailableException.class);
        verify(mongo, never()).insert(any(Favourite.class));
    }

    @Test void duplicateInsertReturnsExistingWithoutSecondEvent() {
        Favourite existing = new Favourite(new FavouriteId("buyer", "l1"), Instant.now(), "seller");
        when(listings.findById("l1")).thenReturn(Optional.of(listing(ListingStatus.ACTIVE, "seller")));
        when(mongo.insert(any(Favourite.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(favourites.findById(new FavouriteId("buyer", "l1"))).thenReturn(Optional.of(existing));

        service().add("buyer", "l1");

        verify(events, never()).record(any(), any(), any(), any());
    }

    @Test void duplicateConflictWithoutPersistedFavouriteIsPropagated() {
        when(listings.findById("l1")).thenReturn(Optional.of(listing(ListingStatus.ACTIVE, "seller")));
        when(mongo.insert(any(Favourite.class))).thenThrow(new DuplicateKeyException("unrelated duplicate"));
        when(favourites.findById(new FavouriteId("buyer", "l1"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().add("buyer", "l1")).isInstanceOf(DuplicateKeyException.class);
        verify(events, never()).record(any(), any(), any(), any());
    }

    private FavouriteService service() { return new FavouriteService(favourites, listings, events, mongo, NOOP, new PhotoUrls("cdn.example.test")); }

    private Listing listing(ListingStatus status, String seller) {
        return new Listing("l1", "title", "description", BigDecimal.ONE, Instant.now(), status,
                java.util.List.of(), Set.of(), seller, "category", null, null, Map.of());
    }
}
