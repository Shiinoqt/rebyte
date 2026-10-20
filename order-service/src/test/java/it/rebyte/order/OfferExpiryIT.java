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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import it.rebyte.order.repository.OfferRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

/**
 * OFR-04 (03.1-CONTEXT.md D-04, D-10, D-11): the reconciliation sweep hook
 * expires unpaid holds through {@code POST /internal/offers/expire-due},
 * releases them in catalog through the outbox, and never touches a live or
 * paid order's offer.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class OfferExpiryIT extends OrderIntegrationTestBase {

    private static final CheckoutStub STUB = new CheckoutStub();

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", () -> "http://localhost:" + STUB.port());
        registry.add("spring.cloud.openfeign.client.config.payment-service.url", () -> "http://localhost:" + STUB.port());
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OfferRepository offerRepository;

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Test
    void dueHoldExpiresAndReleasesWhileTheSiblingStaysRejected() {
        String listing = listing();
        String winner = create(listing, "buyer-winner", "80.00");
        String sibling = create(listing, "buyer-sibling", "85.00");
        accept(winner);
        assertThat(row(sibling).get("status")).isEqualTo("REJECTED");
        String hold = (String) row(winner).get("hold_reservation_id");
        backdateExpiry(winner);

        Map<String, Object> body = expireDue();
        assertThat(body.get("expiredCount")).isEqualTo(1);
        assertThat(row(winner).get("status")).isEqualTo("EXPIRED");
        assertThat(eventCount(winner, "offer.expired")).isEqualTo(1);
        assertThat(eventCount(winner, "offer.hold.released")).isEqualTo(1);
        assertThat(payload(winner, "offer.hold.released").get("reservationId")).isEqualTo(hold);
        assertThat(payload(winner, "offer.hold.released").get("listingType")).isEqualTo("HARDWARE");
        assertThat(row(sibling).get("status")).isEqualTo("REJECTED");
        assertThat(row(sibling).get("rejection_reason")).isEqualTo("OTHER_ACCEPTED");

        Map<String, Object> second = expireDue();
        assertThat(second.get("expiredCount")).isEqualTo(0);
        assertThat(eventCount(winner, "offer.expired")).isEqualTo(1);
        assertThat(eventCount(winner, "offer.hold.released")).isEqualTo(1);
    }

    @Test
    void futureHoldIsUntouched() {
        String listing = listing();
        String offer = create(listing, "buyer-future", "80.00");
        accept(offer);
        jdbc.update("update offers set expires_at = ? where id = ?",
                LocalDateTime.now(ZoneOffset.UTC).plusSeconds(5), offer);

        assertThat(expireDue().get("expiredCount")).isEqualTo(0);
        assertThat(row(offer).get("status")).isEqualTo("ACCEPTED");
        assertThat(eventCount(offer, "offer.expired")).isEqualTo(0);
        assertThat(eventCount(offer, "offer.hold.released")).isEqualTo(0);
    }

    @Test
    void pendingOfferWithNullExpiryIsUntouched() {
        String listing = listing();
        String offer = create(listing, "buyer-pending", "80.00");

        assertThat(expireDue().get("expiredCount")).isEqualTo(0);
        assertThat(row(offer).get("status")).isEqualTo("PENDING");
        assertThat(row(offer).get("expires_at")).isNull();
    }

    @Test
    void liveAwaitingPaymentOrderKeepsTheHoldAndTheLink() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        String orderId = checkout(buyer, offerId, listing, "87.5");
        backdateExpiry(offerId);

        assertThat(expireDue().get("expiredCount")).isEqualTo(0);
        assertThat(row(offerId)).containsEntry("status", "ACCEPTED").containsEntry("order_id", orderId);
    }

    @Test
    void paidOrderIsNeverExpired() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        String orderId = checkout(buyer, offerId, listing, "87.5");
        jdbc.update("update orders set status = 'PAID' where id = ?", orderId);
        backdateExpiry(offerId);

        assertThat(expireDue().get("expiredCount")).isEqualTo(0);
        assertThat(row(offerId)).containsEntry("status", "ACCEPTED").containsEntry("order_id", orderId);
        assertThat(eventCount(offerId, "offer.expired")).isZero();
    }

    @Test
    void checkoutLinkAndExpiryExcludeEachOtherAcrossTwentyRounds() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                String listing = listing();
                String offerId = create(listing, "buyer-race-" + round, "80.00");
                accept(offerId);
                Instant expiresAt = Instant.parse((String) rest
                        .exchange("/offers/" + offerId, HttpMethod.GET, new HttpEntity<>(headers("offer-seller")), Map.class)
                        .getBody().get("expiresAt"));
                String orderId = UUID.randomUUID().toString();
                CyclicBarrier barrier = new CyclicBarrier(2);

                Future<Integer> linkResult = pool.submit(() -> {
                    barrier.await();
                    return offerRepository.linkOrder(offerId, orderId, expiresAt.minusSeconds(1));
                });
                Future<Integer> expireResult = pool.submit(() -> {
                    barrier.await();
                    return offerRepository.expireDueHold(offerId, expiresAt.plusSeconds(1));
                });

                int linked = linkResult.get();
                int expired = expireResult.get();
                assertThat(linked + expired).as("round %d: exactly one guarded update changes the row", round).isEqualTo(1);

                Map<String, Object> row = row(offerId);
                if (linked == 1) {
                    assertThat(row).containsEntry("status", "ACCEPTED").containsEntry("order_id", orderId);
                } else {
                    assertThat(row).containsEntry("status", "EXPIRED").containsEntry("order_id", null);
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void staleAcceptClaimIsRecoveredWithoutCountingAsExpired() {
        String listing = listing();
        String offerId = create(listing, "buyer-stale-claim", "80.00");
        String staleHold = UUID.randomUUID().toString();
        jdbc.update("update offers set hold_reservation_id = ?, accept_claimed_at = ? where id = ?",
                staleHold, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5), offerId);

        Map<String, Object> body = expireDue();
        assertThat(body.get("expiredCount")).isEqualTo(0);
        assertThat(row(offerId)).containsEntry("status", "PENDING").containsEntry("hold_reservation_id", null)
                .containsEntry("accept_claimed_at", null);
        assertThat(eventCount(offerId, "offer.hold.released")).isEqualTo(1);
        assertThat(payload(offerId, "offer.hold.released").get("reservationId")).isEqualTo(staleHold);
    }

    @Test
    void freshAcceptClaimIsUntouched() {
        String listing = listing();
        String offerId = create(listing, "buyer-fresh-claim", "80.00");
        String freshHold = UUID.randomUUID().toString();
        jdbc.update("update offers set hold_reservation_id = ?, accept_claimed_at = ? where id = ?",
                freshHold, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5), offerId);

        assertThat(expireDue().get("expiredCount")).isEqualTo(0);
        assertThat(row(offerId)).containsEntry("status", "PENDING").containsEntry("hold_reservation_id", freshHold);
        assertThat(eventCount(offerId, "offer.hold.released")).isZero();
    }

    private String checkout(String buyer, String offerId, String listingId, String expectedTotal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingId);
        body.put("method", "PAYPAL");
        body.put("expectedTotal", expectedTotal);
        body.put("offerId", offerId);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("recipient", "Mario Rossi");
        address.put("street", "Via Roma 1");
        address.put("city", "Milano");
        address.put("postalCode", "20100");
        address.put("country", "IT");
        body.put("address", address);

        HttpHeaders checkoutHeaders = headers(buyer);
        checkoutHeaders.set("Idempotency-Key", "idem-" + UUID.randomUUID());
        ResponseEntity<Map> response = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, checkoutHeaders), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("orderId");
    }

    private void backdateExpiry(String offerId) {
        // Hibernate normalizes an Instant-mapped DATETIME column to
        // UTC-naive storage; a raw JDBC-bound java.sql.Timestamp instead
        // goes through the driver's JVM-local-zone conversion (03.1-06
        // deviation finding).
        jdbc.update("update offers set expires_at = ? where id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), offerId);
    }

    private Map<String, Object> expireDue() {
        ResponseEntity<Map> response = rest.postForEntity("/internal/offers/expire-due", null, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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

    private void accept(String offerId) {
        ResponseEntity<Map> response = rest.exchange("/offers/" + offerId + "/accept", HttpMethod.POST,
                new HttpEntity<>(headers("offer-seller")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    private Map<String, Object> payload(String id, String eventType) {
        String json = jdbc.queryForList("select payload from order_outbox_events where aggregate_id = ? "
                + "and event_type = ? order by id desc", String.class, id, eventType).getFirst();
        return readJson(json);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
