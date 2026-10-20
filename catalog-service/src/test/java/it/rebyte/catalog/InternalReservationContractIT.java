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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.dto.ReserveListingRequest;
import it.rebyte.catalog.dto.ReserveListingResponse;
import it.rebyte.catalog.dto.ReserveListingResponse.Outcome;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.service.ListingReservationService;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

/**
 * Proves the D-17 HTTP contract over real replica-set Mongo ({@link
 * it.rebyte.catalog.support.MongoTestBase}'s {@code MongoDBContainer}
 * auto-initializes as a single-node replica set): a caller-supplied {@code
 * reservationId} reserves once, repeats idempotently with no extra change
 * event, a different id is refused while the listing is taken, and the
 * owning id can release it back to ACTIVE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class InternalReservationContractIT extends CatalogInfraTestBase {
    @Autowired TestRestTemplate http;
    @Autowired ListingRepository listings;
    @Autowired ListingReservationService reservations;
    @Autowired OutboxRepository outbox;
    @Autowired MongoTemplate mongo;

    @Test
    void reserveRepeatsIdempotentlyRejectsAnotherOwnerAndReleases() {
        Listing listing = listing();
        String reservationId = UUID.randomUUID().toString();
        long before = outbox.count();

        ReserveListingResponse first = reserve(listing.getId(), reservationId, listing.getVersion());
        assertThat(first.outcome()).isEqualTo(Outcome.RESERVED);
        assertThat(first.reservationId()).isEqualTo(reservationId);
        assertThat(outbox.count()).isEqualTo(before + 1);

        Listing sold = listings.findById(listing.getId()).orElseThrow();
        assertThat(sold.getStatus()).isEqualTo(ListingStatus.SOLD);

        // Same-ID retry after an unknown outcome: idempotent RESERVED, no extra change event.
        ReserveListingResponse repeat = reserve(listing.getId(), reservationId, sold.getVersion());
        assertThat(repeat.outcome()).isEqualTo(Outcome.RESERVED);
        assertThat(repeat.reservationId()).isEqualTo(reservationId);
        assertThat(outbox.count()).isEqualTo(before + 1);

        // A different caller-supplied id cannot take an already-sold listing.
        ReserveListingResponse other = reserve(listing.getId(), UUID.randomUUID().toString(), sold.getVersion());
        assertThat(other.outcome()).isEqualTo(Outcome.ALREADY_TAKEN);
        assertThat(outbox.count()).isEqualTo(before + 1);

        assertThat(reservations.release(listing.getId(), reservationId)).isTrue();
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    private ReserveListingResponse reserve(String id, String reservationId, Long expectedVersion) {
        return http.exchange("/internal/listings/" + id + "/reserve", HttpMethod.PATCH,
                new HttpEntity<>(new ReserveListingRequest(reservationId, expectedVersion)),
                ReserveListingResponse.class).getBody();
    }

    private Listing listing() {
        String categoryId = UUID.randomUUID().toString();
        mongo.insert(new Category(categoryId, "Test leaf " + categoryId, null, List.of("Hardware"),
                List.of(), List.of(), "admin", Instant.now(), 0L));
        return listings.save(new Listing(UUID.randomUUID().toString(), "probe" + UUID.randomUUID().toString().replace("-", ""),
                "Test listing", BigDecimal.TEN, Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.BANK_TRANSFER),
                "seller", categoryId, new HardwareListing(Condition.GOOD, "Roma", BigDecimal.ZERO), null, Map.of()));
    }
}
