package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.rebyte.order.domain.*;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class FulfilmentShipDeliverIT extends OrderIntegrationTestBase {
    @Autowired TestRestTemplate http;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test void sellerShipsAndBuyerConfirmsDeliveryWithMinimalAtomicEvents() throws Exception {
        String id = seed("PAID", ListingType.HARDWARE);
        var ship = post(id, "ship", "seller", Map.of("carrier", "BRT", "trackingCode", " BRT-123 456 "));
        assertThat(ship.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ship.getBody()).containsEntry("status", "SHIPPED").containsEntry("carrier", "BRT")
                .containsEntry("trackingCode", "BRT-123 456").containsKeys("shippedAt");
        var buyer = request("/orders/" + id, HttpMethod.GET, "buyer", null);
        assertThat(buyer.getBody()).containsEntry("trackingCode", "BRT-123 456");
        assertEvent(id, "order.shipped");
        assertThat(post(id, "ship", "seller", Map.of("carrier", "DHL", "trackingCode", "CHANGED"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(orders.findById(id).orElseThrow().getTrackingCode()).isEqualTo("BRT-123 456");
        assertCode(post(id, "deliver", "seller", null), HttpStatus.FORBIDDEN, "order-action-forbidden");
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.SHIPPED);
        var delivered = post(id, "deliver", "buyer", null);
        assertThat(delivered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(delivered.getBody()).containsEntry("status", "DELIVERED").containsKeys("deliveredAt");
        assertEvent(id, "order.delivered");
        assertCode(post(id, "deliver", "buyer", null), HttpStatus.CONFLICT, "order-transition-conflict");
        assertEvent(id, "order.delivered");
    }

    @Test void roleAndStateGuardsRequireTheActorForEachAction() {
        String id = seed("PAID", ListingType.HARDWARE);
        for (String role : new String[]{"buyer", "admin-operator"}) {
            assertCode(post(id, "ship", role, valid()), HttpStatus.FORBIDDEN, "order-action-forbidden");
        }
        for (String role : new String[]{"seller", "admin-operator"})
            assertCode(post(id, "deliver", role, null), HttpStatus.FORBIDDEN, "order-action-forbidden");
        assertCode(post(id, "ship", "stranger", valid()), HttpStatus.NOT_FOUND, "order-not-found");
        assertCode(post(id, "deliver", "stranger", null), HttpStatus.NOT_FOUND, "order-not-found");
        for (String status : new String[]{"AWAITING_PAYMENT", "CANCELLED", "REFUNDED", "DELIVERED"}) {
            String other = seed(status, ListingType.HARDWARE);
            assertCode(post(other, "ship", "seller", valid()), HttpStatus.CONFLICT, "order-transition-conflict");
        }
        assertCode(post(seed("PAID", ListingType.SOFTWARE), "ship", "seller", valid()),
                HttpStatus.CONFLICT, "order-transition-conflict");
        for (String status : new String[]{"AWAITING_PAYMENT", "PAID", "CANCELLED", "REFUNDED", "DELIVERED"})
            assertCode(post(seed(status, ListingType.HARDWARE), "deliver", "buyer", null),
                    HttpStatus.CONFLICT, "order-transition-conflict");
        assertCode(post(seed("SHIPPED", ListingType.SOFTWARE), "deliver", "buyer", null),
                HttpStatus.CONFLICT, "order-transition-conflict");
        jdbc.update("update orders set refund_requested_at = CURRENT_TIMESTAMP(6) where id = ?", id);
        assertCode(post(id, "ship", "seller", valid()), HttpStatus.CONFLICT, "order-refund-pending");
        String shipped = seed("SHIPPED", ListingType.HARDWARE);
        jdbc.update("update orders set refund_requested_at = CURRENT_TIMESTAMP(6) where id = ?", shipped);
        assertCode(post(shipped, "deliver", "buyer", null), HttpStatus.CONFLICT, "order-refund-pending");
    }

    @Test void invalidTrackingAndCarrierNeverChangeTheOrder() {
        String id = seed("PAID", ListingType.HARDWARE);
        assertThat(post(id, "ship", "seller", Map.of("carrier", "ROYAL_MAIL", "trackingCode", "123"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (String code : new String[]{" ", "A".repeat(65), "A\tB", "\tA", "A\t"}) {
            assertThat(post(id, "ship", "seller", Map.of("carrier", "BRT", "trackingCode", code))
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    private void assertEvent(String id, String type) throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                Long.class, id, type)).isEqualTo(1L);
        String payload = jdbc.queryForObject("select payload from order_outbox_events where aggregate_id=? and event_type=?",
                String.class, id, type);
        var tree = mapper.readTree(payload);
        java.util.Set<String> keys = new java.util.HashSet<>();
        tree.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("eventId", "orderId", "listingId", "listingType", "buyerSub",
                "sellerSub", "listingTitle", "carrier", "trackingCode", "occurredAt");
        assertThat(tree.path("listingTitle").asText()).isEqualTo("Private title");
        assertThat(tree.path("carrier").asText()).isEqualTo("BRT");
        assertThat(tree.path("trackingCode").asText()).isEqualTo("BRT-123 456");
        assertThat(payload).doesNotContain("price", "amount", "address");
    }
    private String seed(String status, ListingType type) {
        String id = UUID.randomUUID().toString();
        orders.saveAndFlush(new Order(id, "buyer", "seller", UUID.randomUUID().toString(), type,
                "Private title", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, PaymentMethod.PAYPAL,
                id, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(1800)));
        jdbc.update("update orders set status=? where id=?", status, id);
        return id;
    }
    private Map<String,String> valid() { return Map.of("carrier", "BRT", "trackingCode", "123"); }
    private void assertCode(ResponseEntity<Map> result, HttpStatus status, String code) {
        assertThat(result.getStatusCode()).isEqualTo(status);
        assertThat(result.getBody().toString()).contains(code);
    }
    private ResponseEntity<Map> post(String id, String action, String sub, Object body) {
        return request("/orders/" + id + "/" + action, HttpMethod.POST, sub, body);
    }
    private ResponseEntity<Map> request(String path, HttpMethod method, String sub, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(sub)); headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }
}
