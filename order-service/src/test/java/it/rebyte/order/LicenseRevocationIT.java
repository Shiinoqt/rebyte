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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;

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
class LicenseRevocationIT extends OrderIntegrationTestBase {

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private OrderRepository orders;
    @Autowired private LicenseRepository licenses;
    @Autowired private OrderPaymentTransitionService transitions;

    @Test
    void softwareRefundRevokesAndRetainsLicenceWhileOtherOrdersRemainIndependent() throws Exception {
        String buyer = "buyer-" + UUID.randomUUID();
        String seller = "seller-" + UUID.randomUUID();
        String singleListing = UUID.randomUUID().toString();
        Order single = deliver(ListingType.SOFTWARE, buyer, seller, singleListing);
        String singleKey = licenses.findByOrderId(single.getId()).orElseThrow().getKey();
        PaymentResultEvent refund = refund(single, "refund-single");
        transitions.apply("payment.refunded", refund);
        transitions.apply("payment.refunded", refund);

        Order refunded = orders.findById(single.getId()).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        var revoked = licenses.findByOrderId(single.getId()).orElseThrow();
        assertThat(revoked.getStatus()).isEqualTo(LicenseStatus.REVOKED);
        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from licenses where order_id = ?", Long.class, single.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'licence.revoked'", Long.class, single.getId())).isEqualTo(1);
        Instant revokedAt = revoked.getRevokedAt();
        PaymentResultEvent replay = refund(single, UUID.randomUUID().toString());
        transitions.apply("payment.refunded", replay);
        assertThat(jdbc.queryForObject("select count(*) from order_inbox_events where event_id = ?", Long.class,
                replay.eventId())).isEqualTo(1);
        assertThat(orders.findById(single.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'order.refunded'", Long.class, single.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'licence.revoked'", Long.class, single.getId())).isEqualTo(1);
        var replayedLicense = licenses.findByOrderId(single.getId()).orElseThrow();
        assertThat(replayedLicense.getStatus()).isEqualTo(LicenseStatus.REVOKED);
        assertThat(replayedLicense.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(jdbc.queryForObject("select count(*) from licenses where order_id = ?", Long.class, single.getId()))
                .isEqualTo(1);
        String revokedPayload = jdbc.queryForObject("select payload from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'licence.revoked'", String.class, single.getId());
        assertThat(revokedPayload).doesNotContain(singleKey, "key");
        assertThat(rest.getForObject("/internal/licences?buyerSub=" + buyer + "&listingId=" + singleListing,
                Map.class).get("active")).isEqualTo(false);
        ResponseEntity<Map> mine = get("/licences/me", buyer);
        Map mineItem = (Map) ((java.util.List) mine.getBody().get("items")).stream()
                .filter(item -> ((Map) item).get("orderId").equals(single.getId())).findFirst().orElseThrow();
        assertThat(mineItem.get("status")).isEqualTo("REVOKED");

        String repeatListing = UUID.randomUUID().toString();
        Order repeatOne = deliver(ListingType.SOFTWARE, buyer, seller, repeatListing);
        Order repeatTwo = deliver(ListingType.SOFTWARE, buyer, seller, repeatListing);
        transitions.apply("payment.refunded", refund(repeatOne, "refund-repeat"));
        assertThat(rest.getForObject("/internal/licences?buyerSub=" + buyer + "&listingId=" + repeatListing,
                Map.class).get("active")).isEqualTo(true);
        assertThat(licenses.findByOrderId(repeatTwo.getId()).orElseThrow().getStatus()).isEqualTo(LicenseStatus.ACTIVE);

        Order hardware = deliver(ListingType.HARDWARE, buyer, seller, UUID.randomUUID().toString());
        assertThat(orders.findById(hardware.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        transitions.apply("payment.refunded", refund(hardware, "refund-hardware"));
        assertThat(count(hardware.getId(), "licence.revoked")).isZero();
        assertThat(licenses.findByOrderId(hardware.getId())).isEmpty();

        Order legacy = seedLegacyPaidSoftware(buyer, seller);
        transitions.apply("payment.refunded", refund(legacy, "refund-legacy"));
        assertThat(orders.findById(legacy.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(count(legacy.getId(), "licence.revoked")).isZero();
        assertThat(licenses.findByOrderId(legacy.getId())).isEmpty();
    }

    private Order deliver(ListingType type, String buyer, String seller, String listingId) {
        String id = UUID.randomUUID().toString();
        String providerOrder = "PP-" + id;
        String capture = "CAP-" + id;
        String operation = "OP-" + id;
        Order order = new Order(id, buyer, seller, listingId, type, "Private software title", new BigDecimal("12.37"),
                type == ListingType.HARDWARE ? new BigDecimal("4.19") : BigDecimal.ZERO,
                type == ListingType.HARDWARE ? new BigDecimal("16.56") : new BigDecimal("12.37"),
                PaymentMethod.PAYPAL, "idem-" + id, UUID.randomUUID().toString(), Instant.now(),
                Instant.now().plusSeconds(1800));
        orders.saveAndFlush(order);
        jdbc.update("update orders set checkout_phase = ?, provider_order_id = ?, capture_operation_id = ? where id = ?",
                CheckoutPhase.CAPTURE_AUTHORIZED.name(), providerOrder, operation, id);
        transitions.apply("payment.succeeded", new PaymentResultEvent("paid-" + id, id, providerOrder,
                capture, operation, false, Instant.now()));
        return orders.findById(id).orElseThrow();
    }

    private Order seedLegacyPaidSoftware(String buyer, String seller) {
        String id = UUID.randomUUID().toString();
        Order legacy = new Order(id, buyer, seller, UUID.randomUUID().toString(), ListingType.SOFTWARE,
                "Legacy title", new BigDecimal("12.37"), BigDecimal.ZERO, new BigDecimal("12.37"),
                PaymentMethod.PAYPAL, "idem-" + id, UUID.randomUUID().toString(), Instant.now(),
                Instant.now().plusSeconds(1800));
        orders.saveAndFlush(legacy);
        jdbc.update("update orders set status = 'PAID', paid_at = ?, provider_order_id = ?, provider_capture_id = ? "
                + "where id = ?", java.sql.Timestamp.from(Instant.now()), "PP-" + id, "CAP-" + id, id);
        return orders.findById(id).orElseThrow();
    }

    private PaymentResultEvent refund(Order order, String eventId) {
        return new PaymentResultEvent(eventId, order.getId(), order.getProviderOrderId(),
                order.getProviderCaptureId(), null, false, Instant.now());
    }

    private long count(String orderId, String type) {
        return jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? and event_type = ?",
                Long.class, orderId, type);
    }

    private ResponseEntity<Map> get(String path, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
