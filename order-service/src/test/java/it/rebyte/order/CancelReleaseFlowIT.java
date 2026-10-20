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
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.order.domain.CancellationReason;
import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class CancelReleaseFlowIT extends OrderIntegrationTestBase {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void buyerCancelAtomicallyClearsGuardAndRecordsRelease() {
        Order order = createOrder("buyer");
        ResponseEntity<Map> response = post("/orders/" + order.getId() + "/cancel", "buyer");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Order cancelled = orders.findById(order.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCheckoutPhase()).isEqualTo(CheckoutPhase.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo(CancellationReason.BUYER_ABORTED);
        assertThat(cancelled.getActiveListingGuard()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'order.cancelled'", Long.class, order.getId())).isEqualTo(1);

        assertThat(post("/orders/" + order.getId() + "/cancel", "buyer").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/orders/" + order.getId() + "/cancel", "stranger").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void captureAuthorizationWinsAndCancellationRetainsGuard() {
        Order order = createOrder("buyer");
        jdbc.update("update orders set checkout_phase = 'READY' where id = ?", order.getId());
        ResponseEntity<Map> granted = http.postForEntity("/internal/orders/" + order.getId()
                + "/capture-authorizations", Map.of("operationId", "operation-1"), Map.class);
        assertThat(granted.getBody().get("outcome")).isEqualTo("GRANTED");

        assertThat(post("/orders/" + order.getId() + "/cancel", "buyer").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        Order retained = orders.findById(order.getId()).orElseThrow();
        assertThat(retained.getCheckoutPhase()).isEqualTo(CheckoutPhase.CAPTURE_AUTHORIZED);
        assertThat(retained.getActiveListingGuard()).isEqualTo(order.getListingId());
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'order.cancelled'", Long.class, order.getId())).isZero();
    }

    private Order createOrder(String buyer) {
        String id = UUID.randomUUID().toString();
        String listingId = UUID.randomUUID().toString();
        Order order = new Order(id, buyer, "seller", listingId, ListingType.HARDWARE,
                "Hardware", new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("10.00"),
                PaymentMethod.PAYPAL, "idem-" + id, UUID.randomUUID().toString(), Instant.now(),
                Instant.now().plusSeconds(1800));
        order.setActiveListingGuard(listingId);
        order.setReservationId(id);
        return orders.saveAndFlush(order);
    }

    private ResponseEntity<Map> post(String path, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }
}
