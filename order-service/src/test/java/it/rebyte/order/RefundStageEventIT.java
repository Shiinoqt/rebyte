package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.rebyte.order.domain.*;
import it.rebyte.order.messaging.PaymentResultEvent;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.service.OrderPaymentTransitionService;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest
class RefundStageEventIT extends OrderIntegrationTestBase {
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired OrderPaymentTransitionService transitions;

    @Test void refundReleasesOnlyPaidReservationsAndReplayEmitsNothing() throws Exception {
        for (String status : new String[]{"PAID", "SHIPPED", "DELIVERED"}) {
            String id = UUID.randomUUID().toString();
            orders.saveAndFlush(new Order(id, "buyer", "seller", UUID.randomUUID().toString(), ListingType.HARDWARE,
                    "Private title", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, PaymentMethod.PAYPAL,
                    id, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(1800)));
            jdbc.update("update orders set status=?, provider_order_id=?, provider_capture_id=? where id=?", status, "PP-"+id, "CAP-"+id, id);
            var event = new PaymentResultEvent(UUID.randomUUID().toString(), id, "PP-"+id, "CAP-"+id, null, false, Instant.now());
            transitions.apply("payment.refunded", event);
            transitions.apply("payment.refunded", event);
            var replay = new PaymentResultEvent(UUID.randomUUID().toString(), id, event.providerOrderId(),
                    event.providerCaptureId(), null, false, Instant.now());
            transitions.apply("payment.refunded", replay);
            assertThat(jdbc.queryForObject("select count(*) from order_inbox_events where event_id=?", Long.class,
                    replay.eventId())).isEqualTo(1L);
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type='order.refunded'", Long.class, id)).isEqualTo(1L);
            String payload = jdbc.queryForObject("select payload from order_outbox_events where aggregate_id=? and event_type='order.refunded'", String.class, id);
            var tree = mapper.readTree(payload);
            assertThat(tree.path("refundedFromStatus").asText()).isEqualTo(status);
            if (status.equals("PAID")) assertThat(tree.path("reservationId").asText()).isEqualTo(id);
            else assertThat(tree.path("reservationId").isNull()).isTrue();
            java.util.Set<String> keys = new java.util.HashSet<>(); tree.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactlyInAnyOrder("eventId", "orderId", "listingId", "listingType", "buyerSub",
                    "sellerSub", "listingTitle", "reservationId", "cancellationReason", "refundedFromStatus", "occurredAt");
            assertThat(tree.path("listingTitle").asText()).isEqualTo("Private title");
            assertThat(payload).doesNotContain("price", "amount", "address");
        }
    }
}
