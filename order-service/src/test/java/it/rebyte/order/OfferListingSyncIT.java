package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.config.RabbitConfig;
import it.rebyte.order.controller.OrderDeadLetterController;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.messaging.ListingLifecycleEvent;
import it.rebyte.order.repository.OfferRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

/**
 * D-01, D-02, D-05 (03.1-CONTEXT.md): pending offers track the listing's availability and
 * price through {@code listing.updated}/{@code listing.removed}, and a removed listing expires
 * an unconverted hold without a catalog release. Also covers the {@code order.listing-lifecycle}
 * DLQ replay path (Task 2).
 */
@SpringBootTest
class OfferListingSyncIT extends OrderIntegrationTestBase {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    private static final CheckoutStub STUB = new CheckoutStub();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> true);
        registry.add("rebyte.outbox.relay.initial-delay-ms", () -> 3600000);
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url",
                () -> "http://localhost:" + STUB.port());
    }

    @Autowired
    private OfferRepository offerRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private OrderDeadLetterController deadLetters;

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Test
    void listingBecomingUnavailableRejectsAllPendingOffersIdempotentlyWithoutRevival() throws Exception {
        String listing = registerListing(true, "120.00", 5L);
        String first = createPendingOffer(listing, "buyer-a", "80.00");
        String second = createPendingOffer(listing, "buyer-b", "85.00");
        STUB.registerListing(listing, "offer-seller", "Hardware", new BigDecimal("120.00"), new BigDecimal("7.50"),
                "HARDWARE", List.of("PAYPAL"), false, 6L);

        publishListingEvent(RebyteExchanges.LISTING_UPDATED, listing);
        awaitStatus(first, "REJECTED");
        awaitStatus(second, "REJECTED");
        assertThat(row(first)).containsEntry("rejection_reason", "LISTING_UNAVAILABLE");
        assertThat(row(second)).containsEntry("rejection_reason", "LISTING_UNAVAILABLE");
        assertThat(eventCount(first, "offer.rejected")).isEqualTo(1);
        assertThat(eventCount(second, "offer.rejected")).isEqualTo(1);

        // Redelivery: nothing left PENDING, so it is a no-op.
        publishListingEvent(RebyteExchanges.LISTING_UPDATED, listing);
        Thread.sleep(300);
        assertThat(eventCount(first, "offer.rejected")).isEqualTo(1);
        assertThat(eventCount(second, "offer.rejected")).isEqualTo(1);

        // The listing becoming ACTIVE again never revives an offer rejected this way.
        STUB.registerListing(listing, "offer-seller", "Hardware", new BigDecimal("120.00"), new BigDecimal("7.50"),
                "HARDWARE", List.of("PAYPAL"), true, 7L);
        publishListingEvent(RebyteExchanges.LISTING_UPDATED, listing);
        Thread.sleep(300);
        assertThat(row(first).get("status")).isEqualTo("REJECTED");
        assertThat(row(second).get("status")).isEqualTo("REJECTED");
    }

    @Test
    void priceAtOrAboveAnOfferRejectsItAndLeavesALowerOfferPending() throws Exception {
        String listing = registerListing(true, "90.00", 5L);
        String atPrice = createPendingOffer(listing, "buyer-at", "80.00");
        String below = createPendingOffer(listing, "buyer-below", "79.99");
        STUB.registerListing(listing, "offer-seller", "Hardware", new BigDecimal("80.00"), new BigDecimal("7.50"),
                "HARDWARE", List.of("PAYPAL"), true, 6L);

        publishListingEvent(RebyteExchanges.LISTING_UPDATED, listing);
        awaitStatus(atPrice, "REJECTED");
        assertThat(row(atPrice)).containsEntry("rejection_reason", "PRICE_CHANGED");
        assertThat(row(below).get("status")).isEqualTo("PENDING");
    }

    @Test
    void removalRejectsPendingAndExpiresUnlinkedHoldButLeavesALinkedOfferOnAnotherListingUntouched()
            throws Exception {
        String listingA = registerListing(true, "120.00", 5L);
        String pending = createPendingOffer(listingA, "buyer-pending", "80.00");
        String unlinkedAccepted = createAcceptedOffer(listingA, "buyer-unlinked", "90.00", null);

        String listingB = registerListing(true, "200.00", 5L);
        String orderId = UUID.randomUUID().toString();
        String linkedAccepted = createAcceptedOffer(listingB, "buyer-linked", "150.00", orderId);

        publishListingEvent(RebyteExchanges.LISTING_REMOVED, listingA);
        awaitStatus(pending, "REJECTED");
        assertThat(row(pending)).containsEntry("rejection_reason", "LISTING_UNAVAILABLE");
        awaitStatus(unlinkedAccepted, "EXPIRED");
        assertThat(eventCount(unlinkedAccepted, "offer.expired")).isEqualTo(1);
        assertThat(eventCount(unlinkedAccepted, "offer.hold.released")).isZero();

        publishListingEvent(RebyteExchanges.LISTING_REMOVED, listingB);
        Thread.sleep(300);
        assertThat(row(linkedAccepted)).containsEntry("status", "ACCEPTED").containsEntry("order_id", orderId);
    }

    @Test
    void claimInFlightBlocksRejectionOnAnUnavailableListing() throws Exception {
        String listing = registerListing(true, "120.00", 5L);
        String claimed = createPendingOffer(listing, "buyer-claimed", "80.00");
        jdbc.update("update offers set hold_reservation_id = ?, accept_claimed_at = ? where id = ?",
                UUID.randomUUID().toString(), LocalDateTime.now(ZoneOffset.UTC), claimed);
        STUB.registerListing(listing, "offer-seller", "Hardware", new BigDecimal("120.00"), new BigDecimal("7.50"),
                "HARDWARE", List.of("PAYPAL"), false, 6L);

        publishListingEvent(RebyteExchanges.LISTING_UPDATED, listing);
        Thread.sleep(300);
        assertThat(row(claimed).get("status")).isEqualTo("PENDING");
        assertThat(eventCount(claimed, "offer.rejected")).isZero();
    }

    /**
     * Unlike an unavailable listing, a price drop rejects a claimed offer too: the re-price bumps
     * the catalog version, so the in-flight accept's reserve is fenced on its stale version and
     * can never win, and skipping the claimed row would leave other offers above the new price.
     */
    @Test
    void claimInFlightDoesNotBlockRejectionOnAPriceDrop() throws Exception {
        String listing = registerListing(true, "90.00", 5L);
        String claimed = createPendingOffer(listing, "buyer-claimed", "80.00");
        String unclaimed = createPendingOffer(listing, "buyer-unclaimed", "85.00");
        jdbc.update("update offers set hold_reservation_id = ?, accept_claimed_at = ? where id = ?",
                UUID.randomUUID().toString(), LocalDateTime.now(ZoneOffset.UTC), claimed);
        STUB.registerListing(listing, "offer-seller", "Hardware", new BigDecimal("80.00"), new BigDecimal("7.50"),
                "HARDWARE", List.of("PAYPAL"), true, 6L);

        publishListingEvent(RebyteExchanges.LISTING_UPDATED, listing);
        awaitStatus(claimed, "REJECTED");
        awaitStatus(unclaimed, "REJECTED");
        assertThat(row(claimed)).containsEntry("rejection_reason", "PRICE_CHANGED");
        assertThat(eventCount(claimed, "offer.rejected")).isEqualTo(1);
    }

    @Test
    void updatedForAListingWithNoOffersMakesNoAvailabilityCall() throws Exception {
        String listing = registerListing(true, "120.00", 5L);

        publishListingEvent(RebyteExchanges.LISTING_UPDATED, listing);
        Thread.sleep(300);
        assertThat(STUB.availabilityCalls(listing)).isZero();
    }

    @Test
    void catalogFailureDuringTheReadParksTheEventAndAdminReplayCompletesIt() throws Exception {
        String listing = registerListing(true, "120.00", 5L);
        String offer = createPendingOffer(listing, "buyer-parked", "80.00");
        STUB.failAvailability(listing, true);

        publishListingEvent(RebyteExchanges.LISTING_UPDATED, listing);
        awaitDeadLetters(RabbitConfig.LISTING_LIFECYCLE_DLQ, 1);
        assertThat(row(offer).get("status")).isEqualTo("PENDING");

        STUB.failAvailability(listing, false);
        STUB.registerListing(listing, "offer-seller", "Hardware", new BigDecimal("120.00"), new BigDecimal("7.50"),
                "HARDWARE", List.of("PAYPAL"), false, 6L);

        var replay = deadLetters.replayOne(admin(), RabbitConfig.LISTING_LIFECYCLE_DLQ);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("X-Audit-Id")).isNotBlank();
        awaitStatus(offer, "REJECTED");
        assertThat(row(offer)).containsEntry("rejection_reason", "LISTING_UNAVAILABLE");

        var second = deadLetters.replayOne(admin(), RabbitConfig.LISTING_LIFECYCLE_DLQ);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void malformedBodyIsDeadLetteredWithoutRequeue() throws Exception {
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.LISTING_UPDATED, "not-json");
        awaitDeadLetters(RabbitConfig.LISTING_LIFECYCLE_DLQ, 1);
        // Drain so this parked message does not leak into other tests.
        rabbit.receive(RabbitConfig.LISTING_LIFECYCLE_DLQ, 1000);
    }

    @Test
    void nonAdminReplayIsForbiddenAndAnUnknownQueueIs404() {
        assertThatThrownBy(() -> deadLetters.replayOne(buyer(), RabbitConfig.LISTING_LIFECYCLE_DLQ))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> deadLetters.replayOne(admin(), "some.other.dlq"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private String registerListing(boolean available, String price, long version) {
        String id = UUID.randomUUID().toString();
        STUB.registerListing(id, "offer-seller", "Hardware", new BigDecimal(price), new BigDecimal("7.50"),
                "HARDWARE", List.of("PAYPAL"), available, version);
        return id;
    }

    private String createPendingOffer(String listingId, String buyer, String amount) {
        String id = UUID.randomUUID().toString();
        Offer offer = new Offer(id, listingId, buyer, "offer-seller", "Hardware", new BigDecimal(amount),
                new BigDecimal(amount), Instant.now());
        offerRepository.saveAndFlush(offer);
        return id;
    }

    /** A null {@code orderId} leaves the offer unlinked; a non-null one leaves it linked to a live order. */
    private String createAcceptedOffer(String listingId, String buyer, String amount, String orderId) {
        String id = createPendingOffer(listingId, buyer, amount);
        jdbc.update("update offers set status = 'ACCEPTED', pending_guard = null, hold_reservation_id = ?, "
                + "expires_at = ?, order_id = ? where id = ?", UUID.randomUUID().toString(),
                LocalDateTime.now(ZoneOffset.UTC).plusHours(24), orderId, id);
        return id;
    }

    private void publishListingEvent(String routingKey, String listingId) throws Exception {
        String payload = mapper.writeValueAsString(new ListingLifecycleEvent(listingId, 1L, Instant.now(), null,
                null));
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, routingKey, payload);
    }

    private Jwt admin() {
        return Jwt.withTokenValue("admin").header("alg", "none").subject("admin")
                .claim("cognito:groups", List.of("ADMIN")).build();
    }

    private Jwt buyer() {
        return Jwt.withTokenValue("buyer").header("alg", "none").subject("buyer").build();
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("select * from offers where id = ?", id);
    }

    private int eventCount(String id, String eventType) {
        return jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = ?", Integer.class, id, eventType);
    }

    private void awaitStatus(String offerId, String expectedStatus) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String last = null;
        while (System.nanoTime() < deadline) {
            last = (String) row(offerId).get("status");
            if (expectedStatus.equals(last)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(last).isEqualTo(expectedStatus);
    }

    private void awaitDeadLetters(String queue, int wanted) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        long depth = -1;
        while (System.nanoTime() < deadline) {
            depth = rabbit.execute(channel -> channel.messageCount(queue));
            if (depth == wanted) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(depth).isEqualTo(wanted);
    }
}
