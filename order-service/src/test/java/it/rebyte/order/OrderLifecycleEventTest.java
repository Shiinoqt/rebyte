package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.messaging.OrderLifecycleEvent;

/**
 * 03.1-06-PLAN.md Task 2: every order variant's release payload carries
 * exactly its persisted reservation id (03.1-CONTEXT.md D-07, D-08, D-21) —
 * the invariant test accepted into this plan after the assumption-delta
 * decision that promoted "reservationId" from a derived value to a
 * per-order-path persisted field.
 */
class OrderLifecycleEventTest {

    @Test
    void releasePayloadCarriesExactlyThePersistedReservationId() {
        String id = UUID.randomUUID().toString();
        Order hardware = order(id, ListingType.HARDWARE);
        OrderLifecycleEvent hardwareEvent = OrderLifecycleEvent.from(hardware);
        assertThat(hardwareEvent.reservationId()).isEqualTo(id);
        assertThat(hardwareEvent.listingTitle()).isEqualTo("Listing");

        Order software = order(UUID.randomUUID().toString(), ListingType.SOFTWARE);
        assertThat(OrderLifecycleEvent.from(software).reservationId()).isNull();

        String holdId = UUID.randomUUID().toString();
        hardware.linkOffer(UUID.randomUUID().toString(), holdId);
        assertThat(OrderLifecycleEvent.from(hardware).reservationId()).isEqualTo(holdId);

        hardware.setReservationId(null);
        assertThat(OrderLifecycleEvent.from(hardware).reservationId()).isNull();
    }

    private Order order(String id, ListingType type) {
        return new Order(id, "buyer", "seller", UUID.randomUUID().toString(), type, "Listing",
                new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("10.00"), PaymentMethod.PAYPAL,
                "idem-" + id, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(1800));
    }
}
