package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

import it.rebyte.order.support.OrderIntegrationTestBase;

/**
 * OFR-03 (03.1-CONTEXT.md D-09): the seller rejects and the buyer withdraws a
 * PENDING offer; neither party can end an ACCEPTED hold early, and rejecting
 * or withdrawing never revives a previously rejected sibling.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class OfferTransitionIT extends OrderIntegrationTestBase {

    private static final CheckoutStub STUB = new CheckoutStub();
    private static final String SELLER = "offer-seller";

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", () -> "http://localhost:" + STUB.port());
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
    void sellerRejectsThenBuyerSendsAgainAndWithdraws() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String rejected = create(listing, buyer, "80.00");

        ResponseEntity<Map> rejectResponse = reject(rejected, SELLER);
        assertThat(rejectResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejectResponse.getBody().get("status")).isEqualTo("REJECTED");
        assertThat(rejectResponse.getBody().get("rejectionReason")).isEqualTo("SELLER");
        assertThat(row(rejected).get("pending_guard")).isNull();
        assertThat(eventCount(rejected, "offer.rejected")).isEqualTo(1);
        assertThat(payload(rejected, "offer.rejected").get("rejectionReason")).isEqualTo("SELLER");

        // pending_guard cleared: the same buyer may send a new offer on that listing
        String second = create(listing, buyer, "82.00");
        assertThat(second).isNotEqualTo(rejected);

        ResponseEntity<Map> withdrawResponse = withdraw(second, buyer);
        assertThat(withdrawResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(withdrawResponse.getBody().get("status")).isEqualTo("WITHDRAWN");
        assertThat(eventCount(second, "offer.withdrawn")).isEqualTo(1);

        assertThat(withdraw(second, buyer).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reject(rejected, SELLER).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void nonActingPartiesAndStrangersGet404OnRejectAndWithdraw() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");

        assertThat(reject(offerId, buyer).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withdraw(offerId, SELLER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reject(offerId, "admin-operator").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withdraw(offerId, "admin-operator").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String stranger = "stranger-" + UUID.randomUUID();
        assertThat(reject(offerId, stranger).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withdraw(offerId, stranger).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(row(offerId).get("status")).isEqualTo("PENDING");
    }

    @Test
    void acceptedHoldCannotBeEndedEarlyByRejectOrWithdraw() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");
        accept(offerId);
        String hold = (String) row(offerId).get("hold_reservation_id");

        assertThat(reject(offerId, SELLER).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(withdraw(offerId, buyer).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> row = row(offerId);
        assertThat(row.get("status")).isEqualTo("ACCEPTED");
        assertThat(row.get("hold_reservation_id")).isEqualTo(hold);
    }

    @Test
    void noCounterOrAmountChangeEndpointExists() {
        String listing = listing();
        String buyer = "buyer-" + UUID.randomUUID();
        String offerId = create(listing, buyer, "80.00");

        ResponseEntity<Map> counter = rest.exchange("/offers/" + offerId + "/counter", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", "90.00"), headers(buyer)), Map.class);
        assertThat(counter.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);

        ResponseEntity<Map> put = rest.exchange("/offers/" + offerId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("amount", "90.00"), headers(buyer)), Map.class);
        assertThat(put.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);

        assertThat((BigDecimal) row(offerId).get("amount")).isEqualByComparingTo(new BigDecimal("80.00"));
    }

    @Test
    void expiringTheWinningHoldNeverRevivesAnOfferRejectedOnAcceptance() {
        String listing = listing();
        String winner = create(listing, "buyer-winner-" + UUID.randomUUID(), "80.00");
        String sibling = create(listing, "buyer-sibling-" + UUID.randomUUID(), "85.00");
        accept(winner);
        assertThat(row(sibling).get("status")).isEqualTo("REJECTED");
        assertThat(row(sibling).get("rejection_reason")).isEqualTo("OTHER_ACCEPTED");

        backdateExpiry(winner);
        ResponseEntity<Map> expireResponse = rest.postForEntity("/internal/offers/expire-due", null, Map.class);
        assertThat(expireResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(row(winner).get("status")).isEqualTo("EXPIRED");

        assertThat(row(sibling).get("status")).isEqualTo("REJECTED");
        assertThat(row(sibling).get("rejection_reason")).isEqualTo("OTHER_ACCEPTED");
    }

    private void backdateExpiry(String offerId) {
        // Hibernate normalizes an Instant-mapped DATETIME column to
        // UTC-naive storage; a raw JDBC-bound java.sql.Timestamp instead
        // goes through the driver's JVM-local-zone conversion (03.1-06
        // deviation finding).
        jdbc.update("update offers set expires_at = ? where id = ?",
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1), offerId);
    }

    private ResponseEntity<Map> reject(String offerId, String subject) {
        return rest.exchange("/offers/" + offerId + "/reject", HttpMethod.POST,
                new HttpEntity<>(headers(subject)), Map.class);
    }

    private ResponseEntity<Map> withdraw(String offerId, String subject) {
        return rest.exchange("/offers/" + offerId + "/withdraw", HttpMethod.POST,
                new HttpEntity<>(headers(subject)), Map.class);
    }

    private void accept(String offerId) {
        ResponseEntity<Map> response = rest.exchange("/offers/" + offerId + "/accept", HttpMethod.POST,
                new HttpEntity<>(headers(SELLER)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String listing() {
        String id = UUID.randomUUID().toString();
        STUB.registerListing(id, SELLER, "Hardware", new BigDecimal("120.00"),
                new BigDecimal("7.50"), "HARDWARE", List.of("PAYPAL"), true, 5L);
        return id;
    }

    private String create(String listing, String buyer, String amount) {
        ResponseEntity<Map> response = rest.exchange("/offers", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", listing, "amount", amount), headers(buyer)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
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
