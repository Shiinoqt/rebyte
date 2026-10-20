package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.messaging.OrderLifecycleListener;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.service.ListingReservationService;
import it.rebyte.catalog.support.MongoTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RefundStageReleaseIT extends MongoTestBase {
    @Autowired ListingRepository listings;
    @Autowired ListingReservationService reservations;
    @Autowired OrderLifecycleListener lifecycle;

    @Test
    void onlyRefundsFromPaidReleaseAHardwareReservation() throws Exception {
        Listing listing = listings.save(new Listing(UUID.randomUUID().toString(), "Hardware", "Test item",
                BigDecimal.TEN, Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.BANK_TRANSFER),
                "seller", "category", new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, Map.of()));
        String reservationId = UUID.randomUUID().toString();
        assertThat(reservations.reserve(listing.getId(), reservationId, listing.getVersion()).outcome().name())
                .isEqualTo("RESERVED");
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.SOLD);

        lifecycle.onOrderReleased(refundEvent(listing.getId(), null, "SHIPPED"));
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.SOLD);

        lifecycle.onOrderReleased(refundEvent(listing.getId(), reservationId, "PAID"));
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    private Message refundEvent(String listingId, String reservationId, String refundedFromStatus) throws Exception {
        String body = """
                {"eventId":"event-1","orderId":"order-1","listingId":"%s","listingType":"HARDWARE",
                 "buyerSub":"buyer","sellerSub":"seller","reservationId":%s,"cancellationReason":null,
                 "refundedFromStatus":"%s","occurredAt":"2026-09-30T12:00:00Z"}
                """.formatted(listingId, reservationId == null ? "null" : "\"" + reservationId + "\"", refundedFromStatus);
        return new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties());
    }
}
