package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.repository.ModerationRepository;
import it.rebyte.catalog.service.ListingRemovalService;
import it.rebyte.catalog.service.ListingCache;
import it.rebyte.catalog.error.ListingAlreadyRemovedException;
import it.rebyte.catalog.error.ModerationReasonRequiredException;
import it.rebyte.catalog.error.ListingNotOwnedException;

@ExtendWith(MockitoExtension.class)
class RemovalBranchTest {
    @Mock MongoTemplate mongo;
    @Mock ModerationRepository moderations;
    @Mock DomainEventRecorder events;
    @Mock ListingCache listingCache;

    private static final PlatformTransactionManager NOOP = new PlatformTransactionManager() {
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException { return new SimpleTransactionStatus(); }
        public void commit(TransactionStatus status) throws TransactionException { }
        public void rollback(TransactionStatus status) throws TransactionException { }
    };

    @Test void sellerWithdrawalDoesNotCreateModeration() {
        Listing active = listing(ListingStatus.ACTIVE, "seller");
        when(mongo.findById("l1", Listing.class)).thenReturn(active);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Listing.class))).thenReturn(removed());

        service().remove("seller", false, "l1", null);

        verify(moderations, never()).save(any());
    }

    @Test void adminRequiresNonBlankReasonBeforeTransition() {
        when(mongo.findById("l1", Listing.class)).thenReturn(listing(ListingStatus.ACTIVE, "seller"));
        assertThatThrownBy(() -> service().remove("admin", true, "l1", " "))
                .isInstanceOf(ModerationReasonRequiredException.class);
        verify(mongo, never()).findAndModify(any(), any(), any(FindAndModifyOptions.class), eq(Listing.class));
    }

    @Test void adminWithReasonCreatesModeration() {
        Listing active = listing(ListingStatus.ACTIVE, "seller");
        when(mongo.findById("l1", Listing.class)).thenReturn(active);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Listing.class))).thenReturn(removed());
        service().remove("admin", true, "l1", "offensive");
        verify(moderations).save(any());
    }

    @Test void unrelatedCallerIsForbidden() {
        when(mongo.findById("l1", Listing.class)).thenReturn(listing(ListingStatus.ACTIVE, "seller"));
        assertThatThrownBy(() -> service().remove("intruder", false, "l1", null))
                .isInstanceOf(ListingNotOwnedException.class);
    }

    @Test void ownerReasonIsRejected() {
        when(mongo.findById("l1", Listing.class)).thenReturn(listing(ListingStatus.ACTIVE, "seller"));
        assertThatThrownBy(() -> service().remove("seller", false, "l1", "because"))
                .isInstanceOf(ModerationReasonRequiredException.class);
    }

    @Test void removedListingCannotBeRemovedAgain() {
        when(mongo.findById("l1", Listing.class)).thenReturn(listing(ListingStatus.REMOVED, "seller"));
        assertThatThrownBy(() -> service().remove("admin", true, "l1", "reason"))
                .isInstanceOf(ListingAlreadyRemovedException.class);
        verify(moderations, never()).save(any());
    }

    @Test void sellerCannotWithdrawSoldListingButAdminCanModerateIt() {
        when(mongo.findById("l1", Listing.class)).thenReturn(listing(ListingStatus.SOLD, "seller"));
        assertThatThrownBy(() -> service().remove("seller", false, "l1", null))
                .isInstanceOf(ListingNotOwnedException.class);
        verify(mongo, never()).findAndModify(any(), any(), any(FindAndModifyOptions.class), eq(Listing.class));

        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Listing.class)))
                .thenReturn(removed());
        service().remove("admin", true, "l1", "offensive");
        verify(listingCache, times(1)).evict("l1");
    }

    private ListingRemovalService service() { return new ListingRemovalService(mongo, moderations, events, listingCache, NOOP); }
    private Listing listing(ListingStatus status, String seller) {
        return new Listing("l1", "title", "description", BigDecimal.ONE, Instant.now(), status,
                java.util.List.of(), Set.of(), seller, "category", null, null, Map.of());
    }
    private Listing removed() { return listing(ListingStatus.REMOVED, "seller"); }
}
