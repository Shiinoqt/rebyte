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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.LicenseStatus;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.messaging.PaymentResultEvent;
import it.rebyte.order.repository.LicenseRepository;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.service.OrderPaymentTransitionService;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class SoftwareDeliveryLicenseIT extends OrderIntegrationTestBase {

    private static final CheckoutStub STUB = new CheckoutStub();

    @DynamicPropertySource
    static void stubUrls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", () -> "http://localhost:" + STUB.port());
        registry.add("spring.cloud.openfeign.client.config.payment-service.url", () -> "http://localhost:" + STUB.port());
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderRepository orders;
    @Autowired private LicenseRepository licenses;
    @Autowired private OrderPaymentTransitionService transitions;

    @AfterAll
    static void stopStub() { STUB.stop(); }

    @Test
    void checkoutSnapshotDrivesTransactionalDeliveryAndLegacyOrdersAreNotBackfilled() {
        String listingId = UUID.randomUUID().toString();
        registerSoftware(listingId, "ACME-################");
        String orderId = checkout(listingId);
        assertThat(orders.findById(orderId).orElseThrow().getLicenseKeyPattern()).isEqualTo("ACME-################");
        authorize(orders.findById(orderId).orElseThrow());
        STUB.registerListing(listingId, "seller", "Updated title", new BigDecimal("12.37"), BigDecimal.ZERO,
                "SOFTWARE", List.of("PAYPAL"), true, 2L, "NEW-################");

        PaymentResultEvent success = success(orderId, "event-1");
        transitions.apply("payment.succeeded", success);
        Order delivered = orders.findById(orderId).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(delivered.getPaidAt()).isEqualTo(delivered.getDeliveredAt());
        var issued = licenses.findByOrderId(orderId).orElseThrow();
        assertThat(issued.getKey()).matches("ACME-[0-9A-HJKMNP-TV-Z]{16}");
        assertThat(issued.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type in ('order.paid','order.delivered','licence.issued')", Long.class, orderId)).isEqualTo(3);
        transitions.apply("payment.succeeded", success);
        transitions.apply("payment.succeeded", success(orderId, "event-2"));
        assertThat(licenses.findByOrderId(orderId)).hasValueSatisfying(reloaded ->
                assertThat(reloaded.getKey()).isEqualTo(issued.getKey()));
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'licence.issued'", Long.class, orderId)).isEqualTo(1);

        Order noSnapshot = seedOrder(ListingType.SOFTWARE, null, OrderStatus.AWAITING_PAYMENT);
        authorize(noSnapshot);
        transitions.apply("payment.succeeded", success(noSnapshot.getId(), "default-event"));
        assertThat(licenses.findByOrderId(noSnapshot.getId()).orElseThrow().getKey())
                .matches("RBYT-[0-9A-HJKMNP-TV-Z]{5}(?:-[0-9A-HJKMNP-TV-Z]{5}){3}");

        Order invalidSnapshot = seedOrder(ListingType.SOFTWARE, "ABC", OrderStatus.AWAITING_PAYMENT);
        authorize(invalidSnapshot);
        transitions.apply("payment.succeeded", success(invalidSnapshot.getId(), "invalid-event"));
        assertThat(licenses.findByOrderId(invalidSnapshot.getId()).orElseThrow().getKey())
                .matches("RBYT-[0-9A-HJKMNP-TV-Z]{5}(?:-[0-9A-HJKMNP-TV-Z]{5}){3}");

        Order hardware = seedOrder(ListingType.HARDWARE, null, OrderStatus.AWAITING_PAYMENT);
        authorize(hardware);
        transitions.apply("payment.succeeded", success(hardware.getId(), "hardware-event"));
        assertThat(orders.findById(hardware.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(licenses.findByOrderId(hardware.getId())).isEmpty();

        Order legacy = seedOrder(ListingType.SOFTWARE, null, OrderStatus.PAID);
        jdbc.update("update orders set paid_at = ? where id = ?", java.sql.Timestamp.from(Instant.now()), legacy.getId());
        authorize(legacy);
        transitions.apply("payment.succeeded", success(legacy.getId(), "legacy-event"));
        assertThat(orders.findById(legacy.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(licenses.findByOrderId(legacy.getId())).isEmpty();
    }

    private void registerSoftware(String listingId, String pattern) {
        STUB.registerListing(listingId, "seller", "Software", new BigDecimal("12.37"), BigDecimal.ZERO,
                "SOFTWARE", List.of("PAYPAL"), true, 1L, pattern);
    }

    private String checkout(String listingId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingId);
        body.put("method", "PAYPAL");
        body.put("expectedTotal", "12.37");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token("buyer"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "idem-" + UUID.randomUUID());
        ResponseEntity<Map> response = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return String.valueOf(response.getBody().get("orderId"));
    }

    private Order seedOrder(ListingType type, String pattern, OrderStatus status) {
        String id = UUID.randomUUID().toString();
        Order order = new Order(id, "buyer", "seller", UUID.randomUUID().toString(), type, "Title",
                new BigDecimal("12.37"), type == ListingType.HARDWARE ? new BigDecimal("4.19") : BigDecimal.ZERO,
                type == ListingType.HARDWARE ? new BigDecimal("16.56") : new BigDecimal("12.37"),
                PaymentMethod.PAYPAL, "idem-" + id, UUID.randomUUID().toString(), Instant.now(),
                Instant.now().plusSeconds(1800));
        order.snapshotLicenseKeyPattern(pattern);
        orders.saveAndFlush(order);
        if (status == OrderStatus.PAID) {
            jdbc.update("update orders set status = 'PAID', paid_at = ? where id = ?",
                    java.sql.Timestamp.from(Instant.now()), id);
        }
        return order;
    }

    private void authorize(Order order) {
        String providerOrderId = "PP-" + order.getId();
        jdbc.update("update orders set checkout_phase = ?, provider_order_id = ? where id = ?",
                CheckoutPhase.CAPTURE_AUTHORIZED.name(), providerOrderId, order.getId());
    }

    private PaymentResultEvent success(String orderId, String eventId) {
        String providerOrderId = orders.findById(orderId).orElseThrow().getProviderOrderId();
        return new PaymentResultEvent(eventId, orderId, providerOrderId, "CAP-" + orderId,
                "OP-" + orderId, false, Instant.now());
    }
}
