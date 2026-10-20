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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class OfferTracerIT extends OrderIntegrationTestBase {
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
    void createdOfferHasSnapshotOutboxPayloadAndParticipantVisibility() throws Exception {
        String listing = UUID.randomUUID().toString();
        String buyer = "buyer-" + UUID.randomUUID();
        String seller = "seller-" + UUID.randomUUID();
        STUB.registerListing(listing, seller, "ThinkPad T480", new BigDecimal("120.00"),
                new BigDecimal("7.50"), "HARDWARE", List.of("PAYPAL"), true, 3L);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buyer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange("/offers", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", listing, "amount", "80.00"), headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map body = response.getBody();
        String id = (String) body.get("id");
        assertThat(id).isNotBlank();
        assertThat(body.get("status")).isEqualTo("PENDING");
        assertThat(new BigDecimal(body.get("amount").toString())).isEqualByComparingTo("80.00");
        assertThat(new BigDecimal(body.get("askingPrice").toString())).isEqualByComparingTo("120.00");
        assertThat(body.get("listingTitle")).isEqualTo("ThinkPad T480");
        assertThat(body.get("sellerSub")).isEqualTo(seller);
        assertThat(body.get("expiresAt")).isNull();
        assertThat(body).doesNotContainKeys("pendingGuard", "holdReservationId", "acceptClaimedAt");
        assertThat(jdbc.queryForObject("select pending_guard from offers where id = ?", String.class, id))
                .isEqualTo(listing);

        List<String> payloads = jdbc.queryForList("select payload from order_outbox_events "
                + "where event_type = 'offer.created' and aggregate_id = ?", String.class, id);
        assertThat(payloads).hasSize(1);
        JsonNode event = mapper.readTree(payloads.getFirst());
        for (String key : List.of("eventId", "offerId", "listingId", "listingTitle", "buyerSub", "sellerSub",
                "amount", "askingPrice", "status", "rejectionReason", "expiresAt", "occurredAt")) {
            assertThat(event.has(key)).as(key).isTrue();
        }
        assertThat(event.get("offerId").asText()).isEqualTo(id);
        assertThat(event.get("status").asText()).isEqualTo("PENDING");
        assertThat(event.get("rejectionReason").isNull()).isTrue();
        assertThat(event.get("expiresAt").isNull()).isTrue();

        assertThat(get(id, buyer).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(id, seller).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(id, "admin-operator").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> stranger = get(id, "stranger-" + UUID.randomUUID());
        assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stranger.getBody().get("title")).isEqualTo("offer-not-found");
    }

    private ResponseEntity<Map> get(String id, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(subject);
        return rest.exchange("/offers/" + id, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
