package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@TestPropertySource(properties = "rebyte.offers.hold-duration=PT2H")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class OfferAcceptanceIT extends OrderIntegrationTestBase {
    private static final CheckoutStub STUB = new CheckoutStub();

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", () -> "http://localhost:" + STUB.port());
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @AfterAll static void stopStub() { STUB.stop(); }

    @Test
    void acceptsOneOfferAndRejectsEveryPendingSiblingIncludingEqualAmount() throws Exception {
        String listing = listing();
        String first = create(listing, "buyer-a", "80.00");
        String second = create(listing, "buyer-b", "85.00");
        String equal = create(listing, "buyer-c", "80.00");

        Instant before = Instant.now();
        ResponseEntity<Map> response = accept(first);
        Instant after = Instant.now();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("ACCEPTED");
        Instant expiresAt = Instant.parse((String) response.getBody().get("expiresAt"));
        assertThat(expiresAt).isBetween(before.plus(Duration.ofHours(2)).minusSeconds(5),
                after.plus(Duration.ofHours(2)).plusSeconds(5));
        Map<String, Object> winner = row(first);
        String hold = (String) winner.get("hold_reservation_id");
        assertThat(UUID.fromString(hold).toString()).isEqualTo(hold);
        assertThat(hold).isNotEqualTo(first);
        assertThat(winner.get("pending_guard")).isNull();
        assertThat(winner.get("accept_claimed_at")).isNull();
        assertThat(STUB.reserveCalls().stream().filter(call -> listing.equals(call.get("listingId"))).toList())
                .singleElement().satisfies(call -> {
                    assertThat(call.get("reservationId")).isEqualTo(hold);
                    assertThat(((Number) call.get("expectedVersion")).longValue()).isEqualTo(5L);
                });
        for (String id : List.of(second, equal)) {
            assertThat(row(id)).containsEntry("status", "REJECTED")
                    .containsEntry("rejection_reason", "OTHER_ACCEPTED")
                    .containsEntry("pending_guard", null);
            JsonNode event = event(id, "offer.rejected");
            assertThat(event.get("rejectionReason").asText()).isEqualTo("OTHER_ACCEPTED");
        }
        JsonNode accepted = event(first, "offer.accepted");
        assertThat(accepted.get("expiresAt").isNull()).isFalse();
        assertThat(accepted.get("rejectionReason").isNull()).isTrue();
        assertThat(eventCount(first, "offer.accepted")).isEqualTo(1);
        assertThat(eventCount(second, "offer.rejected")).isEqualTo(1);
        assertThat(eventCount(equal, "offer.rejected")).isEqualTo(1);
    }

    @Test
    void acceptingSoleOfferProducesNoRejectedEvent() {
        String listing = listing();
        String offer = create(listing, "sole-buyer", "80.00");
        assertThat(accept(offer).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where event_type = 'offer.rejected' "
                + "and aggregate_id in (select id from offers where listing_id = ?)", Integer.class, listing))
                .isZero();
        assertThat(eventCount(offer, "offer.accepted")).isEqualTo(1);
    }

    @Test
    void siblingAlreadyWithdrawnRemainsUntouched() {
        String listing = listing();
        String first = create(listing, "winner-buyer", "80.00");
        String withdrawn = create(listing, "withdrawn-buyer", "85.00");
        jdbc.update("update offers set status = 'WITHDRAWN', pending_guard = null where id = ?", withdrawn);
        assertThat(accept(first).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(row(withdrawn).get("status")).isEqualTo("WITHDRAWN");
        assertThat(eventCount(withdrawn, "offer.rejected")).isZero();
    }

    @Test
    void onlySnapshotSellerCanAcceptAndTerminalOfferIsRejected() {
        String listing = listing();
        String offer = create(listing, "buyer-authorization", "80.00");
        for (String caller : List.of("buyer-authorization", "admin-operator", "stranger")) {
            ResponseEntity<Map> denied = accept(offer, caller);
            assertProblem(denied, HttpStatus.NOT_FOUND, "offer-not-found");
        }
        jdbc.update("update offers set status = 'WITHDRAWN', pending_guard = null where id = ?", offer);
        assertProblem(accept(offer), HttpStatus.CONFLICT, "offer-not-pending");
        assertThat(STUB.reserveCalls().stream().filter(call -> listing.equals(call.get("listingId")))).isEmpty();
    }

    @Test
    void unavailableFreshListingRejectsOfferWithoutReserve() {
        String listing = listing();
        String first = create(listing, "buyer-unavailable", "80.00");
        String sibling = create(listing, "buyer-unavailable-sibling", "85.00");
        STUB.registerListing(listing, "offer-seller", "Hardware", new BigDecimal("120.00"),
                new BigDecimal("7.50"), "HARDWARE", List.of("PAYPAL"), false, 6L);
        assertProblem(accept(first), HttpStatus.CONFLICT, "listing-unavailable");
        assertRejected(first, "LISTING_UNAVAILABLE");
        assertRejected(sibling, "LISTING_UNAVAILABLE");
        assertThat(STUB.reserveCalls().stream().filter(call -> listing.equals(call.get("listingId")))).isEmpty();
    }

    @Test
    void missingFreshListingRejectsOfferWithoutReserve() {
        String listing = listing();
        String offer = create(listing, "buyer-missing", "80.00");
        STUB.removeListing(listing);
        assertProblem(accept(offer), HttpStatus.CONFLICT, "listing-unavailable");
        assertRejected(offer, "LISTING_UNAVAILABLE");
        assertThat(STUB.reserveCalls().stream().filter(call -> listing.equals(call.get("listingId")))).isEmpty();
    }

    @Test
    void priceAtOfferAmountRejectsBeforeReserve() {
        String listing = listing();
        String offer = create(listing, "buyer-reprice", "80.00");
        STUB.registerListing(listing, "offer-seller", "Hardware", new BigDecimal("80.00"),
                new BigDecimal("7.50"), "HARDWARE", List.of("PAYPAL"), true, 6L);
        assertProblem(accept(offer), HttpStatus.CONFLICT, "offer-price-changed");
        assertRejected(offer, "PRICE_CHANGED");
        assertThat(STUB.reserveCalls().stream().filter(call -> listing.equals(call.get("listingId")))).isEmpty();
    }

    @Test
    void definiteReserveLossRejectsUnclaimedSiblingsWithoutRelease() {
        for (String outcome : List.of("ALREADY_TAKEN", "NOT_FOUND")) {
            String listing = listing();
            String offer = create(listing, "buyer-" + outcome, "80.00");
            String sibling = create(listing, "sibling-" + outcome, "85.00");
            STUB.setReserveOutcome(listing, outcome);
            assertProblem(accept(offer), HttpStatus.CONFLICT, "listing-unavailable");
            assertRejected(offer, "LISTING_UNAVAILABLE");
            assertRejected(sibling, "LISTING_UNAVAILABLE");
            assertThat(eventCount(offer, "offer.hold.released")).isZero();
        }
    }

    @Test
    void staleVersionKeepsOfferAndSiblingsPendingWithoutRelease() {
        String listing = listing();
        String offer = create(listing, "buyer-stale", "80.00");
        String sibling = create(listing, "sibling-stale", "85.00");
        STUB.setReserveOutcome(listing, "NOT_RESERVABLE");
        assertProblem(accept(offer), HttpStatus.CONFLICT, "offer-accept-conflict");
        assertPendingUnclaimed(offer);
        assertPendingUnclaimed(sibling);
        assertThat(eventCount(offer, "offer.hold.released")).isZero();
    }

    @Test
    void unknownReserveOutcomeCompensatesAndRetryUsesFreshHold() throws Exception {
        String listing = listing();
        String offer = create(listing, "buyer-unknown", "80.00");
        String sibling = create(listing, "sibling-unknown", "85.00");
        rest.getRestTemplate().setRequestFactory(new SimpleClientHttpRequestFactory());
        STUB.failReserve(listing, 2);
        assertProblem(accept(offer), HttpStatus.SERVICE_UNAVAILABLE, "offer-accept-unavailable");
        assertPendingUnclaimed(offer);
        assertPendingUnclaimed(sibling);
        assertThat(eventCount(offer, "offer.hold.released")).isEqualTo(1);
        String firstHold = (String) STUB.reserveCalls().stream()
                .filter(call -> listing.equals(call.get("listingId"))).findFirst().orElseThrow().get("reservationId");
        assertThat(event(offer, "offer.hold.released").get("reservationId").asText()).isEqualTo(firstHold);
        assertThat(accept(offer).getStatusCode()).isEqualTo(HttpStatus.OK);
        String laterHold = (String) STUB.reserveCalls().stream()
                .filter(call -> listing.equals(call.get("listingId"))).reduce((a, b) -> b).orElseThrow()
                .get("reservationId");
        assertThat(laterHold).isNotEqualTo(firstHold);
    }

    @Test
    void lostFinalTransitionReleasesTheReservedHold() throws Exception {
        String listing = listing();
        String offer = create(listing, "buyer-lost", "80.00");
        CountDownLatch release = new CountDownLatch(1);
        STUB.holdReserve(listing, release);
        CompletableFuture<ResponseEntity<Map>> inFlight = CompletableFuture.supplyAsync(() -> accept(offer));
        try {
            awaitReserveCalls(listing, 1);
            jdbc.update("update offers set status = 'WITHDRAWN', pending_guard = null where id = ?", offer);
        } finally {
            release.countDown();
        }
        assertProblem(inFlight.get(10, TimeUnit.SECONDS), HttpStatus.CONFLICT, "offer-not-pending");
        assertThat(row(offer).get("status")).isEqualTo("WITHDRAWN");
        assertThat(eventCount(offer, "offer.hold.released")).isEqualTo(1);
        String hold = (String) STUB.reserveCalls().stream().filter(call -> listing.equals(call.get("listingId")))
                .findFirst().orElseThrow().get("reservationId");
        assertThat(event(offer, "offer.hold.released").get("reservationId").asText()).isEqualTo(hold);
    }

    private void awaitReserveCalls(String listing, int count) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (STUB.reserveCalls().stream().filter(call -> listing.equals(call.get("listingId"))).count() >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("reserve call did not arrive");
    }

    private void assertRejected(String id, String reason) {
        assertThat(row(id)).containsEntry("status", "REJECTED")
                .containsEntry("rejection_reason", reason)
                .containsEntry("hold_reservation_id", null)
                .containsEntry("accept_claimed_at", null);
        assertThat(eventCount(id, "offer.rejected")).isEqualTo(1);
    }

    private void assertPendingUnclaimed(String id) {
        assertThat(row(id)).containsEntry("status", "PENDING")
                .containsEntry("hold_reservation_id", null)
                .containsEntry("accept_claimed_at", null);
    }

    private void assertProblem(ResponseEntity<Map> response, HttpStatus status, String title) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("title")).isEqualTo(title);
    }

    private String listing() {
        String id = UUID.randomUUID().toString();
        STUB.registerListing(id, "offer-seller", "Hardware", new BigDecimal("120.00"),
                new BigDecimal("7.50"), "HARDWARE", List.of("PAYPAL"), true, 5L);
        return id;
    }

    private String create(String listing, String buyer, String amount) {
        ResponseEntity<Map> response = rest.exchange("/offers", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", listing, "amount", amount), headers(buyer)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private ResponseEntity<Map> accept(String id) {
        return accept(id, "offer-seller");
    }

    private ResponseEntity<Map> accept(String id, String subject) {
        return rest.exchange("/offers/" + id + "/accept", HttpMethod.POST,
                new HttpEntity<>(headers(subject)), Map.class);
    }

    private HttpHeaders headers(String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(subject);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select * from offers where id = ?", id);
    }

    private int eventCount(String id, String eventType) {
        return jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = ?", Integer.class, id, eventType);
    }

    private JsonNode event(String id, String eventType) throws Exception {
        return mapper.readTree(jdbc.queryForObject("select payload from order_outbox_events where aggregate_id = ? "
                + "and event_type = ?", String.class, id, eventType));
    }
}
