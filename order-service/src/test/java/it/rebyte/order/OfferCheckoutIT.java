package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import it.rebyte.order.support.OrderIntegrationTestBase;

/**
 * Task 1 (03.1-06-PLAN.md; 03.1-CONTEXT.md D-07, D-08, D-12, D-14): the
 * offering buyer checks out during the hold at the accepted amount plus
 * shipping, reusing the offer's own catalog reservation, while nobody else
 * can buy the listing during that hold.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class OfferCheckoutIT extends OrderIntegrationTestBase {

    private static final CheckoutStub STUB = new CheckoutStub();

    @DynamicPropertySource
    static void stubUrls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url",
                () -> "http://localhost:" + STUB.port());
        registry.add("spring.cloud.openfeign.client.config.payment-service.url",
                () -> "http://localhost:" + STUB.port());
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Test
    void offeringBuyerPaysAcceptedAmountPlusShippingOnTheReusedHold() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        String holdReservationId = (String) offerRow(offerId).get("hold_reservation_id");

        // Catalog now reports the held listing as SOLD; the offer path must not care.
        STUB.registerListing(listing, "offer-seller", "Hardware", new BigDecimal("120.00"),
                new BigDecimal("7.50"), "HARDWARE", List.of("PAYPAL"), false, 6L);
        int reserveCallsBeforeCheckout = STUB.reserveCalls().size();
        int paymentCallsBefore = STUB.paymentCalls().size();

        ResponseEntity<Map> response = checkout(buyer, offerId, listing, "PAYPAL", "87.5",
                "idem-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = (String) response.getBody().get("orderId");

        Map<String, Object> order = jdbc.queryForMap("select * from orders where id = ?", orderId);
        assertThat(order).containsEntry("item_price", new BigDecimal("80.00"))
                .containsEntry("shipping_cost", new BigDecimal("7.50"))
                .containsEntry("price_paid", new BigDecimal("87.50"))
                .containsEntry("offer_id", offerId)
                .containsEntry("reservation_id", holdReservationId);
        assertThat(offerRow(offerId)).containsEntry("order_id", orderId);

        List<Map<String, Object>> checkoutReserveCalls = STUB.reserveCalls().subList(reserveCallsBeforeCheckout,
                STUB.reserveCalls().size());
        assertThat(checkoutReserveCalls).singleElement().satisfies(call -> {
            assertThat(call.get("listingId")).isEqualTo(listing);
            assertThat(call.get("reservationId")).isEqualTo(holdReservationId);
            assertThat(String.valueOf(call.get("expectedVersion"))).isEqualTo("6");
        });

        List<Map<String, Object>> checkoutPaymentCalls = STUB.paymentCalls().subList(paymentCallsBefore,
                STUB.paymentCalls().size());
        assertThat(checkoutPaymentCalls).singleElement().satisfies(call -> {
            assertThat(String.valueOf(call.get("itemPrice"))).isEqualTo("80.0");
            assertThat(String.valueOf(call.get("pricePaid"))).isEqualTo("87.5");
        });
    }

    @Test
    void priceMismatchIsRejectedWithCurrentTotal() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String stale = create(listing, buyer, "80.00");
        accept(stale);
        ResponseEntity<Map> mismatch = checkout(buyer, stale, listing, "PAYPAL", "87.49",
                "idem-" + UUID.randomUUID());
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mismatch.getBody().get("title")).isEqualTo("price-mismatch");
        assertThat(new BigDecimal(String.valueOf(mismatch.getBody().get("currentTotal"))))
                .isEqualByComparingTo("87.50");
    }

    @Test
    void invalidAmountScaleIsRejected() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String scaled = create(listing, buyer, "80.00");
        accept(scaled);
        ResponseEntity<Map> invalidScale = checkout(buyer, scaled, listing, "PAYPAL", "87.501",
                "idem-" + UUID.randomUUID());
        assertThat(invalidScale.getStatusCode().value()).isEqualTo(422);
        assertThat(invalidScale.getBody().get("title")).isEqualTo("invalid-amount-scale");
    }

    @Test
    void anotherBuyerGets404AndWrongListingGets422() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);

        ResponseEntity<Map> stranger = checkout("stranger-" + UUID.randomUUID(), offerId, listing, "PAYPAL", "87.5",
                "idem-" + UUID.randomUUID());
        assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stranger.getBody().get("title")).isEqualTo("offer-not-found");

        ResponseEntity<Map> wrongListing = checkout(buyer, offerId, UUID.randomUUID().toString(), "PAYPAL", "87.5",
                "idem-" + UUID.randomUUID());
        assertThat(wrongListing.getStatusCode().value()).isEqualTo(422);
        assertThat(wrongListing.getBody().get("title")).isEqualTo("offer-listing-mismatch");
    }

    @Test
    void secondCheckoutAttemptAndCompetingBuyItNowAreBothRefused() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        assertThat(checkout(buyer, offerId, listing, "PAYPAL", "87.5", "idem-" + UUID.randomUUID())
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> secondAttempt = checkout(buyer, offerId, listing, "PAYPAL", "87.5",
                "idem-" + UUID.randomUUID());
        assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(secondAttempt.getBody().get("title")).isEqualTo("offer-not-checkoutable");

        ResponseEntity<Map> competingBuyItNow = buyItNow("other-buyer-" + UUID.randomUUID(), listing, "127.50",
                "idem-" + UUID.randomUUID());
        assertThat(competingBuyItNow.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(competingBuyItNow.getBody().get("title")).isEqualTo("listing-unavailable");
    }

    @Test
    void expiredHoldRefusesCheckout() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        // Hibernate normalizes Instant columns to a UTC-naive DATETIME; a raw
        // java.sql.Timestamp bind would instead go through the JDBC driver's
        // JVM-local-zone conversion and land hours off from what a
        // subsequent Instant-mapped read reconstructs.
        jdbc.update("update offers set expires_at = ? where id = ?",
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1), offerId);

        ResponseEntity<Map> response = checkout(buyer, offerId, listing, "PAYPAL", "87.5",
                "idem-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("title")).isEqualTo("offer-not-checkoutable");
    }

    @Test
    void definiteLostHoldExpiresTheOfferAndLeavesNoOrder() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        STUB.setReserveOutcome(listing, "ALREADY_TAKEN");

        ResponseEntity<Map> response = checkout(buyer, offerId, listing, "PAYPAL", "87.5",
                "idem-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("title")).isEqualTo("listing-unavailable");
        assertThat(offerRow(offerId).get("status")).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'offer.expired'", Integer.class, offerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from orders where buyer_sub = ? and listing_id = ?",
                Integer.class, buyer, listing)).isZero();
    }

    @Test
    void unverifiedHoldLeavesTheOfferUnchanged() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        Map<String, Object> before = offerRow(offerId);
        // Without this, TestRestTemplate's default request factory silently
        // retries the outer POST after the stub's forced 500s, masking the
        // single logical failure this test proves (OfferAcceptanceIT hits
        // the same issue for the identical STUB.failReserve pattern).
        rest.getRestTemplate().setRequestFactory(new SimpleClientHttpRequestFactory());
        STUB.failReserve(listing, 2);

        ResponseEntity<Map> response = checkout(buyer, offerId, listing, "PAYPAL", "87.5",
                "idem-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("title")).isEqualTo("offer-hold-unverified");
        assertThat(offerRow(offerId)).containsEntry("status", before.get("status"))
                .containsEntry("hold_reservation_id", before.get("hold_reservation_id"))
                .containsEntry("expires_at", before.get("expires_at"))
                .containsEntry("order_id", null);
    }

    private String listing() {
        String id = UUID.randomUUID().toString();
        STUB.registerListing(id, "offer-seller", "Hardware", new BigDecimal("120.00"),
                new BigDecimal("7.50"), "HARDWARE", List.of("PAYPAL"), true, 5L);
        return id;
    }

    private String create(String listing, String buyer, String amount) {
        ResponseEntity<Map> response = rest.exchange("/offers", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", listing, "amount", amount), authHeaders(buyer)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private void accept(String offerId) {
        ResponseEntity<Map> response = rest.exchange("/offers/" + offerId + "/accept", HttpMethod.POST,
                new HttpEntity<>(authHeaders("offer-seller")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> checkout(String buyer, String offerId, String listingId, String method,
            String expectedTotal, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingId);
        body.put("method", method);
        body.put("expectedTotal", expectedTotal);
        body.put("offerId", offerId);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("recipient", "Mario Rossi");
        address.put("street", "Via Roma 1");
        address.put("city", "Milano");
        address.put("postalCode", "20100");
        address.put("country", "IT");
        body.put("address", address);

        HttpHeaders headers = authHeaders(buyer);
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.exchange("/orders", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> buyItNow(String buyer, String listingId, String expectedTotal,
            String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingId);
        body.put("method", "PAYPAL");
        body.put("expectedTotal", expectedTotal);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("recipient", "Mario Rossi");
        address.put("street", "Via Roma 1");
        address.put("city", "Milano");
        address.put("postalCode", "20100");
        address.put("country", "IT");
        body.put("address", address);

        HttpHeaders headers = authHeaders(buyer);
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.exchange("/orders", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private HttpHeaders authHeaders(String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> offerRow(String id) {
        return jdbc.queryForMap("select * from offers where id = ?", id);
    }
}
