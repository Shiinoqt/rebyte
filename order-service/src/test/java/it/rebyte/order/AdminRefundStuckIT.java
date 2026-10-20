package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.rebyte.order.client.PaymentInternalClient;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.dto.PaymentStatusResponse;
import it.rebyte.order.dto.RefundResponse;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class AdminRefundStuckIT extends OrderIntegrationTestBase {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private PaymentInternalClient payments;

    @Test
    void refundRequiresAdminAndStaysPendingUntilPaymentEvent() {
        Order order = createOrder();
        jdbc.update("update orders set status = 'PAID', checkout_phase = 'CAPTURE_AUTHORIZED' where id = ?",
                order.getId());
        when(payments.refund(eq(order.getId()), any())).thenReturn(new RefundResponse(order.getId(), "REFUND_PENDING"));

        assertThat(post("/admin/orders/" + order.getId() + "/refund", "buyer", Map.of("reason", "Damaged"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/admin/orders/" + order.getId() + "/refund", "admin-operator", Map.of("reason", " "))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> response = post("/admin/orders/" + order.getId() + "/refund", "admin-operator",
                Map.of("reason", "Damaged"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("REFUND_PENDING");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void stuckViewClassifiesThreeCategoriesAndDeniesNonAdmin() {
        Order timedOut = createOrder(Instant.now().minusSeconds(60));
        Order captured = createOrder();
        Order failed = createOrder();
        jdbc.update("update orders set checkout_phase = 'READY' where id = ?", timedOut.getId());
        jdbc.update("update orders set checkout_phase = 'CAPTURE_AUTHORIZED' where id = ?", captured.getId());
        jdbc.update("update orders set checkout_phase = 'CANCELLED', status = 'CANCELLED', "
                + "cancellation_reason = 'PAYMENT_UNAVAILABLE', active_listing_guard = null where id = ?", failed.getId());
        when(payments.status(captured.getId())).thenReturn(new PaymentStatusResponse(captured.getId(),
                "CAPTURING", "PP-1", "COMPLETED", true));

        assertThat(orders.adminStuckCandidates(Instant.now(), null, null,
                org.springframework.data.domain.PageRequest.of(0, 20)))
                .extracting(Order::getId).contains(timedOut.getId(), captured.getId(), failed.getId());

        assertThat(get("/admin/orders/stuck", "buyer").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> response = get("/admin/orders/stuck", "admin-operator");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        var items = (java.util.List<Map<String, Object>>) response.getBody().get("items");
        assertThat(items).extracting(item -> item.get("category"))
                .contains("awaiting_timeout", "captured_awaiting_webhook", "failed_create");
    }

    private Order createOrder() {
        return createOrder(Instant.now().plusSeconds(1800));
    }

    private Order createOrder(Instant expiryAt) {
        String id = UUID.randomUUID().toString();
        return orders.saveAndFlush(new Order(id, "buyer", "seller", UUID.randomUUID().toString(),
                ListingType.SOFTWARE, "Software", new BigDecimal("10.00"), BigDecimal.ZERO,
                new BigDecimal("10.00"), PaymentMethod.PAYPAL, "idem-" + id,
                UUID.randomUUID().toString(), Instant.now(), expiryAt));
    }

    private ResponseEntity<Map> get(String path, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> post(String path, String subject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
