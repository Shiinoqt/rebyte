package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.dto.ListingAvailability;
import it.rebyte.catalog.dto.ReserveListingResponse.Outcome;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.service.ListingReservationService;
import it.rebyte.catalog.service.PriceChangeService;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

/**
 * Adversarial D-16/D-17 fencing: a release that overtakes a delayed reserve, and a reserve
 * whose {@code expectedVersion} is stale because of a concurrent price edit, must both fail to
 * sell the listing. {@link it.rebyte.catalog.support.MongoTestBase}'s {@code MongoDBContainer}
 * runs a real single-node replica set, so the underlying transaction/version mechanics are the
 * genuine ones, not a mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class LateReservationFenceIT extends CatalogInfraTestBase {
    @Autowired TestRestTemplate http;
    @Autowired ListingRepository listings;
    @Autowired ListingReservationService reservations;
    @Autowired PriceChangeService prices;
    @Autowired MongoTemplate mongo;

    @Test
    void releaseCommittedBeforeADelayedReserveFencesThatReservationIdPermanently() {
        Listing listing = listing();

        // The order was cancelled while the (slow/retried) reserve call for it was still in
        // flight: the listing is still ACTIVE, so this release matches nothing to flip back —
        // but it must still record the tombstone, or the delayed reserve below would sell it.
        assertThat(reservations.release(listing.getId(), "late-order")).isFalse();
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);

        var delayed = reservations.reserve(listing.getId(), "late-order", listing.getVersion());
        assertThat(delayed.outcome()).isEqualTo(Outcome.NOT_RESERVABLE);
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);

        // A different, not-yet-fenced buyer can still reserve the same listing normally.
        var other = reservations.reserve(listing.getId(), "fresh-order", listing.getVersion());
        assertThat(other.outcome()).isEqualTo(Outcome.RESERVED);
    }

    @Test
    void staleExpectedVersionNeverReservesAfterAConcurrentPriceEdit() {
        Listing listing = listing();
        long readVersion = listing.getVersion();

        prices.changePrice("seller", listing.getId(), new BigDecimal("15.00"));

        var stale = reservations.reserve(listing.getId(), "stale-order", readVersion);
        assertThat(stale.outcome()).isEqualTo(Outcome.NOT_RESERVABLE);
        Listing afterStaleAttempt = listings.findById(listing.getId()).orElseThrow();
        assertThat(afterStaleAttempt.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(afterStaleAttempt.getPrice()).isEqualByComparingTo("15.00");

        var fresh = reservations.reserve(listing.getId(), "fresh-order-2", afterStaleAttempt.getVersion());
        assertThat(fresh.outcome()).isEqualTo(Outcome.RESERVED);
    }

    @Test
    void availabilityExposesFreshTitleShippingPaymentMethodsAndVersion() {
        Listing listing = listing();
        prices.changePrice("seller", listing.getId(), new BigDecimal("15.00"));
        Listing current = listings.findById(listing.getId()).orElseThrow();

        ListingAvailability availability = http.getForEntity(
                "/internal/listings/" + listing.getId(), ListingAvailability.class).getBody();

        assertThat(availability.listingTitle()).isEqualTo(current.getTitle());
        assertThat(availability.shippingCost()).isEqualByComparingTo(current.getHardware().getShippingCost());
        assertThat(availability.paymentMethods()).isEqualTo(current.getPaymentMethods());
        assertThat(availability.version()).isEqualTo(current.getVersion());
        assertThat(availability.price()).isEqualByComparingTo("15.00");
    }

    private Listing listing() {
        String categoryId = UUID.randomUUID().toString();
        mongo.insert(new Category(categoryId, "Test leaf " + categoryId, null, List.of("Hardware"),
                List.of(), List.of(), "admin", Instant.now(), 0L));
        return listings.save(new Listing(UUID.randomUUID().toString(), "probe" + UUID.randomUUID().toString().replace("-", ""),
                "Test listing", BigDecimal.TEN, Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.BANK_TRANSFER),
                "seller", categoryId, new HardwareListing(Condition.GOOD, "Roma", new BigDecimal("5.00")), null, Map.of()));
    }
}
