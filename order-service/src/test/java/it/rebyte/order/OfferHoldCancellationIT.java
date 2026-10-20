package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.order.messaging.PaymentResultEvent;
import it.rebyte.order.service.OrderPaymentTransitionService;
import it.rebyte.order.support.OrderIntegrationTestBase;

/**
 * Task 2 (03.1-06-PLAN.md; 03.1-CONTEXT.md D-08, D-10, D-11, D-21): every
 * cancellation and refund path respects the offer hold — a recoverable
 * in-hold cancel hands the hold back to the offer, a past-deadline cancel
 * expires it and releases, and a paid offer can never convert again.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class OfferHoldCancellationIT extends OrderIntegrationTestBase {

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

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private OrderPaymentTransitionService transitions;

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Test
    void inHoldBuyerCancelUnlinksAndAllowsReCheckout() throws Exception {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        String orderId = checkoutSucceeds(buyer, offerId, listing);

        ResponseEntity<Map> cancelled = cancel(orderId, buyer);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(offerRow(offerId)).containsEntry("status", "ACCEPTED").containsEntry("order_id", null);
        Map<String, Object> order = orderRow(orderId);
        assertThat(order).containsEntry("reservation_id", null).containsEntry("cancellation_reason", "BUYER_ABORTED");
        assertThat(eventCount(orderId, "order.cancelled")).isEqualTo(1);
        JsonNode event = event(orderId, "order.cancelled");
        assertThat(event.get("reservationId").isNull()).isTrue();

        ResponseEntity<Map> reCheckout = checkout(buyer, offerId, listing, "PAYPAL", "87.5",
                "idem-" + UUID.randomUUID());
        assertThat(reCheckout.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void paymentUnavailableKeepsTheHoldForRetry() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);

        STUB.setPaymentUnavailable(true);
        try {
            ResponseEntity<Map> response = checkout(buyer, offerId, listing, "PAYPAL", "87.5",
                    "idem-" + UUID.randomUUID());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            STUB.setPaymentUnavailable(false);
        }

        assertThat(offerRow(offerId)).containsEntry("status", "ACCEPTED").containsEntry("order_id", null);
        Map<String, Object> order = jdbc.queryForMap("select * from orders where buyer_sub = ? and listing_id = ?",
                buyer, listing);
        assertThat(order).containsEntry("reservation_id", null)
                .containsEntry("cancellation_reason", "PAYMENT_UNAVAILABLE");

        ResponseEntity<Map> retry = checkout(buyer, offerId, listing, "PAYPAL", "87.5", "idem-" + UUID.randomUUID());
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void paymentFailedThroughTransitionServiceUnlinksTheOffer() throws Exception {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        String orderId = checkoutSucceeds(buyer, offerId, listing);
        String providerOrderId = "stub-payment-" + orderId;

        transitions.apply("payment.failed", new PaymentResultEvent(UUID.randomUUID().toString(), orderId,
                providerOrderId, null, null, true, Instant.now()));

        assertThat(offerRow(offerId)).containsEntry("status", "ACCEPTED").containsEntry("order_id", null);
        Map<String, Object> order = orderRow(orderId);
        assertThat(order).containsEntry("reservation_id", null).containsEntry("cancellation_reason", "PAYMENT_DECLINED");
        assertThat(event(orderId, "order.cancelled").get("reservationId").isNull()).isTrue();
    }

    @Test
    void pastDeadlineCancelExpiresTheOfferAndReleases() throws Exception {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        String orderId = checkoutSucceeds(buyer, offerId, listing);
        String holdReservationId = (String) offerRow(offerId).get("hold_reservation_id");

        jdbc.update("update offers set expires_at = ? where id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), offerId);

        ResponseEntity<Map> cancelled = cancel(orderId, buyer);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(offerRow(offerId)).containsEntry("status", "EXPIRED").containsEntry("order_id", orderId);
        assertThat(eventCount(offerId, "offer.expired")).isEqualTo(1);
        Map<String, Object> order = orderRow(orderId);
        assertThat(order).containsEntry("cancellation_reason", "BUYER_ABORTED");
        assertThat(event(orderId, "order.cancelled").get("reservationId").asText()).isEqualTo(holdReservationId);
    }

    @Test
    void paidThenRefundedConsumesTheOfferPermanently() throws Exception {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        String orderId = checkoutSucceeds(buyer, offerId, listing);
        String holdReservationId = (String) offerRow(offerId).get("hold_reservation_id");
        String providerOrderId = "stub-payment-" + orderId;
        String operationId = "OP-" + orderId;
        jdbc.update("update orders set checkout_phase = 'CAPTURE_AUTHORIZED', capture_operation_id = ? where id = ?",
                operationId, orderId);

        transitions.apply("payment.succeeded", new PaymentResultEvent(UUID.randomUUID().toString(), orderId,
                providerOrderId, "CAP-1", operationId, false, Instant.now()));
        transitions.apply("payment.refunded", new PaymentResultEvent(UUID.randomUUID().toString(), orderId,
                providerOrderId, "CAP-1", operationId, false, Instant.now()));

        assertThat(offerRow(offerId)).containsEntry("status", "ACCEPTED").containsEntry("order_id", orderId);
        assertThat(event(orderId, "order.refunded").get("reservationId").asText()).isEqualTo(holdReservationId);

        ResponseEntity<Map> secondCheckout = checkout(buyer, offerId, listing, "PAYPAL", "87.5",
                "idem-" + UUID.randomUUID());
        assertThat(secondCheckout.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(secondCheckout.getBody().get("title")).isEqualTo("offer-not-checkoutable");
    }

    @Test
    void buyItNowCancelStillCarriesTheOrderIdAsReservationId() throws Exception {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        ResponseEntity<Map> response = buyItNow(buyer, listing, "127.50", "idem-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = (String) response.getBody().get("orderId");

        ResponseEntity<Map> cancelled = cancel(orderId, buyer);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(event(orderId, "order.cancelled").get("reservationId").asText()).isEqualTo(orderId);
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

    private String checkoutSucceeds(String buyer, String offerId, String listing) {
        ResponseEntity<Map> response = checkout(buyer, offerId, listing, "PAYPAL", "87.5",
                "idem-" + UUID.randomUUID());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("orderId");
    }

    private ResponseEntity<Map> checkout(String buyer, String offerId, String listingId, String method,
            String expectedTotal, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingId);
        body.put("method", method);
        body.put("expectedTotal", expectedTotal);
        body.put("offerId", offerId);
        body.put("address", address());

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
        body.put("address", address());

        HttpHeaders headers = authHeaders(buyer);
        headers.set("Idempotency-Key", idempotencyKey);
        return rest.exchange("/orders", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private Map<String, Object> address() {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("recipient", "Mario Rossi");
        address.put("street", "Via Roma 1");
        address.put("city", "Milano");
        address.put("postalCode", "20100");
        address.put("country", "IT");
        return address;
    }

    private ResponseEntity<Map> cancel(String orderId, String buyer) {
        HttpHeaders headers = authHeaders(buyer);
        return rest.exchange("/orders/" + orderId + "/cancel", HttpMethod.POST, new HttpEntity<>(headers),
                Map.class);
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

    private Map<String, Object> orderRow(String id) {
        return jdbc.queryForMap("select * from orders where id = ?", id);
    }

    private int eventCount(String id, String eventType) {
        return jdbc.queryForObject(
                "select count(*) from order_outbox_events where aggregate_id = ? and event_type = ?", Integer.class,
                id, eventType);
    }

    private JsonNode event(String id, String eventType) throws Exception {
        return mapper.readTree(jdbc.queryForObject(
                "select payload from order_outbox_events where aggregate_id = ? and event_type = ? "
                        + "order by id desc limit 1",
                String.class, id, eventType));
    }
}
