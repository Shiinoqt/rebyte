package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class SellerListingsIT extends MongoTestBase {

    @Autowired TestRestTemplate http;
    @Autowired ListingRepository listings;
    @Autowired MongoTemplate mongo;

    @Test
    void anonymousVisitorSeesOnlyActiveListingsNewestFirstWithCursorPaging() {
        String seller = UUID.randomUUID().toString();
        Instant base = Instant.parse("2026-03-01T10:00:00Z");
        Listing newest = listing(seller, ListingStatus.ACTIVE, base.plusSeconds(40), List.of("listings/n/cover"));
        listing(seller, ListingStatus.SOLD, base.plusSeconds(30), List.of());
        Listing middle = listing(seller, ListingStatus.ACTIVE, base.plusSeconds(20), List.of());
        listing(seller, ListingStatus.REMOVED, base.plusSeconds(10), List.of());
        Listing oldest = listing(seller, ListingStatus.ACTIVE, base, List.of());
        listing(UUID.randomUUID().toString(), ListingStatus.ACTIVE, base.plusSeconds(50), List.of());

        ResponseEntity<Map> first = http.getForEntity("/users/" + seller + "/listings?size=2", Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(first)).containsExactly(newest.getId(), middle.getId());
        assertThat(items(first).get(0)).containsEntry("coverUrl", "https://cdn.example.test/listings/n/cover")
                .containsEntry("status", "ACTIVE");
        String cursor = (String) first.getBody().get("nextCursor");
        assertThat(cursor).isNotNull();

        ResponseEntity<Map> second = http.getForEntity("/users/" + seller + "/listings?size=2&cursor=" + cursor,
                Map.class);

        assertThat(ids(second)).containsExactly(oldest.getId());
        assertThat(second.getBody().get("nextCursor")).isNull();
    }

    @Test
    void unknownSellerGetsAnEmptyPage() {
        ResponseEntity<Map> response = http.getForEntity("/users/" + UUID.randomUUID() + "/listings", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(response)).isEmpty();
        assertThat(response.getBody().get("nextCursor")).isNull();
    }

    @Test
    void invalidCursorAndSizeAreRefusedAs422() {
        String seller = UUID.randomUUID().toString();

        for (String query : List.of("cursor=garbage", "size=51")) {
            ResponseEntity<Map> response = http.getForEntity("/users/" + seller + "/listings?" + query, Map.class);
            assertThat(response.getStatusCode().value()).as(query).isEqualTo(422);
            assertThat(response.getBody().toString()).as(query).contains("invalid-listing-cursor");
        }
    }

    @Test
    void sellerStatusPublicationIndexExistsAfterStartup() {
        List<String> names = new ArrayList<>();
        mongo.indexOps(Listing.class).getIndexInfo().forEach(index -> names.add(index.getName()));

        assertThat(names).contains("seller_status_publication");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("items");
    }

    private static List<Object> ids(ResponseEntity<Map> response) {
        return items(response).stream().map(item -> item.get("id")).toList();
    }

    private Listing listing(String seller, ListingStatus status, Instant publication, List<String> photos) {
        return listings.save(new Listing(UUID.randomUUID().toString(), "Item", "Description", new BigDecimal("10.00"),
                publication, status, photos, Set.of(PaymentMethod.PAYPAL), seller, "category",
                new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, Map.of()));
    }
}
