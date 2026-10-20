package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class MyListingsIT extends MongoTestBase {

    @Autowired TestRestTemplate http;
    @Autowired ListingRepository listings;

    @Test
    void ownerSeesEveryStatusNewestFirstAndNeverAnotherSellersListings() {
        String sellerA = UUID.randomUUID().toString();
        String sellerB = UUID.randomUUID().toString();
        Instant base = Instant.parse("2026-03-01T10:00:00Z");
        Listing active = hardware(sellerA, "active", ListingStatus.ACTIVE, base.plusSeconds(40),
                List.of("listings/x/cover"));
        Listing sold = hardware(sellerA, "sold", ListingStatus.SOLD, base.plusSeconds(30), List.of());
        Listing removed = hardware(sellerA, "removed", ListingStatus.REMOVED, base.plusSeconds(20), List.of());
        Listing software = software(sellerA, "software", base.plusSeconds(10));
        hardware(sellerB, "foreign", ListingStatus.ACTIVE, base.plusSeconds(50), List.of());

        ResponseEntity<Map> response = get("/listings/mine", sellerA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = items(response);
        assertThat(items).extracting(item -> item.get("id"))
                .containsExactly(active.getId(), sold.getId(), removed.getId(), software.getId());
        assertThat(items).extracting(item -> item.get("status"))
                .containsExactly("ACTIVE", "SOLD", "REMOVED", "ACTIVE");
        assertThat(response.getBody().get("nextCursor")).isNull();
        assertThat(items.get(0)).containsEntry("coverUrl", "https://cdn.example.test/listings/x/cover")
                .containsEntry("type", "HARDWARE").containsEntry("condition", "GOOD")
                .containsEntry("shippingLocation", "Rome");
        assertThat(items.get(1)).containsEntry("coverUrl", null);
        assertThat(items.get(3)).containsEntry("type", "SOFTWARE").containsEntry("currentVersion", "1.1.0")
                .containsEntry("platforms", List.of("LINUX", "WINDOWS")).containsEntry("condition", null);
    }

    @Test
    void statusFilterKeepsOnlyThatStatus() {
        String seller = UUID.randomUUID().toString();
        Instant base = Instant.parse("2026-03-01T10:00:00Z");
        hardware(seller, "active", ListingStatus.ACTIVE, base.plusSeconds(3), List.of());
        Listing sold = hardware(seller, "sold", ListingStatus.SOLD, base.plusSeconds(2), List.of());
        hardware(seller, "removed", ListingStatus.REMOVED, base.plusSeconds(1), List.of());

        List<Map<String, Object>> items = items(get("/listings/mine?status=SOLD", seller));

        assertThat(items).extracting(item -> item.get("id")).containsExactly(sold.getId());
    }

    @Test
    void cursorPagingCoversEverythingOnceEvenWithEqualPublicationInstants() {
        String seller = UUID.randomUUID().toString();
        Instant base = Instant.parse("2026-03-01T10:00:00Z");
        List<String> expected = new ArrayList<>();
        expected.add(hardware(seller, "newest", ListingStatus.ACTIVE, base.plusSeconds(30), List.of()).getId());
        Instant tie = base.plusSeconds(20);
        List<Listing> tied = List.of(
                hardware(seller, "tie-1", ListingStatus.SOLD, tie, List.of()),
                hardware(seller, "tie-2", ListingStatus.ACTIVE, tie, List.of()));
        tied.stream().map(Listing::getId).sorted(java.util.Comparator.reverseOrder()).forEach(expected::add);
        expected.add(hardware(seller, "older", ListingStatus.REMOVED, base.plusSeconds(10), List.of()).getId());
        expected.add(hardware(seller, "oldest", ListingStatus.ACTIVE, base, List.of()).getId());

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<Map> response = get("/listings/mine?size=2" + (cursor == null ? "" : "&cursor=" + cursor),
                    seller);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            items(response).forEach(item -> seen.add((String) item.get("id")));
            cursor = (String) response.getBody().get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(new HashSet<>(seen)).hasSameSizeAs(seen);
        assertThat(pages).isEqualTo(3);
        assertThat(cursor).isNull();
    }

    @Test
    void anonymousCallIsRefused() {
        ResponseEntity<Map> response = http.exchange("/listings/mine", HttpMethod.GET,
                new HttpEntity<Void>(new HttpHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedCursorAndOutOfRangeSizeAreRefusedAs422() {
        String seller = UUID.randomUUID().toString();

        for (String path : List.of("/listings/mine?cursor=garbage", "/listings/mine?size=51",
                "/listings/mine?size=0")) {
            ResponseEntity<Map> response = get(path, seller);
            assertThat(response.getStatusCode().value()).as(path).isEqualTo(422);
            assertThat(response.getBody().toString()).as(path).contains("invalid-listing-cursor");
        }
    }

    private ResponseEntity<Map> get(String path, String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint(sub));
        return http.exchange(path, HttpMethod.GET, new HttpEntity<Void>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("items");
    }

    private Listing hardware(String seller, String title, ListingStatus status, Instant publication,
            List<String> photos) {
        return listings.save(new Listing(UUID.randomUUID().toString(), title, "Description", new BigDecimal("10.00"),
                publication, status, photos, Set.of(PaymentMethod.PAYPAL), seller, "category",
                new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, Map.of()));
    }

    private Listing software(String seller, String title, Instant publication) {
        return listings.save(new Listing(UUID.randomUUID().toString(), title, "Description", new BigDecimal("10.00"),
                publication, ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL), seller, "category", null,
                new SoftwareListing(Set.of(Platform.WINDOWS, Platform.LINUX), List.of(
                        new Version("1.0.0", Instant.parse("2026-01-01T00:00:00Z"), "Initial", "artifacts/1.0.0"),
                        new Version("1.1.0", Instant.parse("2026-02-01T00:00:00Z"), "Current", "artifacts/1.1.0")),
                        null),
                Map.of()));
    }
}
