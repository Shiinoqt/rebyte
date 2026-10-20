package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import it.rebyte.catalog.domain.*;
import it.rebyte.catalog.error.ListingAlreadyRemovedException;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.repository.ModerationRepository;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.catalog.service.ListingRemovalService;
import it.rebyte.catalog.support.MongoTestBase;

@SpringBootTest
class ModerationIT extends MongoTestBase {
    @Autowired ListingRemovalService removal;
    @Autowired ListingRepository listings;
    @Autowired ModerationRepository moderations;
    @Autowired OutboxRepository outbox;

    @Test void adminRemovalStoresReasonAndRepeatIsRefused() {
        Listing listing = listing();
        removal.remove("admin-1", true, listing.getId(), "offensive");
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.REMOVED);
        assertThat(moderations.findFirstByListingId(listing.getId()).orElseThrow().getReason()).isEqualTo("offensive");
        assertThatThrownBy(() -> removal.remove("admin-1", true, listing.getId(), "again"))
                .isInstanceOf(ListingAlreadyRemovedException.class);
        assertThat(moderations.findAll()).filteredOn(m -> listing.getId().equals(m.getListingId())).hasSize(1);
    }

    @Test void sellerRemovalDoesNotCreateModeration() {
        Listing listing = listing();
        removal.remove("seller", false, listing.getId(), null);

        assertThat(moderations.findFirstByListingId(listing.getId())).isEmpty();
        assertThat(outbox.findAll()).filteredOn(e -> e.getPayload().contains(listing.getId()))
                .filteredOn(e -> RebyteExchanges.LISTING_REMOVED.equals(e.getRoutingKey())).hasSize(1);
    }

    @Test void sellerAndAdminRaceProducesOneRemovalAndModerationOnlyIfAdminWins() throws Exception {
        Listing listing = listing();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var seller = pool.submit(() -> {
                start.await();
                try { removal.remove("seller", false, listing.getId(), null); return "seller"; }
                catch (RuntimeException ignored) { return "lost"; }
            });
            var admin = pool.submit(() -> {
                start.await();
                try { removal.remove("admin-1", true, listing.getId(), "policy"); return "admin"; }
                catch (RuntimeException ignored) { return "lost"; }
            });
            start.countDown();
            String sellerWinner = seller.get(15, TimeUnit.SECONDS);
            String adminWinner = admin.get(15, TimeUnit.SECONDS);
            List<String> outcomes = List.of(sellerWinner, adminWinner);
            assertThat(outcomes.stream().filter("lost"::equals).count()).isEqualTo(1);
            assertThat(outcomes).containsAnyOf("seller", "admin");
            boolean adminWon = "admin".equals(adminWinner);
            assertThat(moderations.findAll()).filteredOn(m -> listing.getId().equals(m.getListingId()))
                    .hasSize(adminWon ? 1 : 0);
        }
        assertThat(outbox.findAll()).filteredOn(e -> e.getPayload().contains(listing.getId()))
                .filteredOn(e -> RebyteExchanges.LISTING_REMOVED.equals(e.getRoutingKey())).hasSize(1);
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.REMOVED);
    }

    private Listing listing() {
        return listings.save(new Listing(UUID.randomUUID().toString(), "Listing", "Description", BigDecimal.TEN,
                Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL), "seller", "category",
                new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, Map.of()));
    }
}
