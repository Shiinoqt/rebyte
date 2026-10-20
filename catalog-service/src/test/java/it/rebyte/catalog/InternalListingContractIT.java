package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Index;

import it.rebyte.catalog.config.RabbitConfig;
import it.rebyte.catalog.domain.*;
import it.rebyte.catalog.dto.*;
import it.rebyte.catalog.dto.ReserveListingResponse.Outcome;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.messaging.OrderLifecycleListener.OrderReleaseEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.catalog.outbox.OutboxRelay;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.service.ListingReservationService;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import it.rebyte.commons.messaging.RebyteExchanges;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class InternalListingContractIT extends CatalogInfraTestBase {
    @Autowired TestRestTemplate http;
    @Autowired ListingRepository listings;
    @Autowired ListingReservationService reservations;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelay relay;
    @Autowired MongoTemplate mongo;
    @Autowired RabbitTemplate rabbit;
    @Autowired ObjectMapper mapper;
    @Autowired Index index;
    @MockitoSpyBean DomainEventRecorder events;

    @Test
    void uncachedReadAndStableReservationOutcomes() {
        Listing listing = listing(false);
        assertThat(http.getForEntity("/internal/listings/" + listing.getId(), ListingAvailability.class).getBody().price())
                .isEqualByComparingTo("10.00");
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(listing.getId())),
                new Update().set("price", new BigDecimal("12.34")), Listing.class);
        var availability = http.getForEntity("/internal/listings/" + listing.getId(), ListingAvailability.class).getBody();
        assertThat(availability.price()).isEqualByComparingTo("12.34");
        assertThat(availability.sellerSub()).isEqualTo(listing.getSellerSub());
        assertThat(availability.available()).isTrue();
        long currentVersion = availability.version();
        assertThat(reserve(listing.getId(), "buyer-1", currentVersion).outcome()).isEqualTo(Outcome.RESERVED);
        assertThat(reserve(listing.getId(), "buyer-2", currentVersion).outcome()).isEqualTo(Outcome.ALREADY_TAKEN);
        assertThat(reserve("missing", "buyer-3", 0L).outcome()).isEqualTo(Outcome.NOT_FOUND);
        Listing software = listing(true);
        assertThat(reserve(software.getId(), "buyer-4", software.getVersion()).outcome()).isEqualTo(Outcome.NOT_RESERVABLE);
        assertThat(http.getForEntity("/internal/listings/missing", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void concurrentBuyersGetExactlyOneReservationAndOneEvent() throws Exception {
        Listing listing = listing(false);
        long before = outbox.count();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return reservations.reserve(listing.getId(), "buyer-a", listing.getVersion()).outcome(); });
            var b = executor.submit(() -> { start.await(); return reservations.reserve(listing.getId(), "buyer-b", listing.getVersion()).outcome(); });
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Outcome.RESERVED, Outcome.ALREADY_TAKEN);
        }
        assertThat(outbox.count()).isEqualTo(before + 1);
    }

    @Test
    void reserveHidesSearchButPreservesDetailAndBrokerReleaseIsIdempotentAcrossSales() throws Exception {
        Listing listing = listing(false);
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.LISTING_CREATED,
                mapper.writeValueAsString(new ListingChangedEvent(listing.getId(), listing.getVersion(), Instant.now())));
        await(() -> searchContains(listing.getTitle()), "initial indexing");
        ReserveListingResponse first = reserve(listing.getId(), "res-1", listing.getVersion());
        assertThat(first.reservationId()).isEqualTo("res-1");
        relay.drain();
        await(() -> !searchContains(listing.getTitle()), "reservation search projection");
        assertThat(index.getRawDocument(listing.getId())).contains("SOLD");
        assertThat(http.getForEntity("/listings/" + listing.getId(), ListingDetailResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        long soldVersion = listings.findById(listing.getId()).orElseThrow().getVersion();
        publishRelease(listing, first, RebyteExchanges.ORDER_CANCELLED);
        await(() -> listings.findById(listing.getId()).orElseThrow().getStatus() == ListingStatus.ACTIVE, "release");
        publishRelease(listing, first, RebyteExchanges.ORDER_CANCELLED);
        relay.drain();
        await(() -> searchContains(listing.getTitle()), "release search projection");
        Listing released = listings.findById(listing.getId()).orElseThrow();
        assertThat(released.getVersion()).isEqualTo(soldVersion + 1);

        var second = reserve(listing.getId(), "res-2", released.getVersion());
        assertThat(second.outcome()).isEqualTo(Outcome.RESERVED);
        // Synchronous duplicate proves the late replay is fully processed before asserting.
        assertThat(reservations.release(listing.getId(), first.reservationId())).isFalse();
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.SOLD);
        publishRelease(listing, second, RebyteExchanges.ORDER_REFUNDED);
        await(() -> listings.findById(listing.getId()).orElseThrow().getStatus() == ListingStatus.ACTIVE, "refund");
    }

    @Test
    void reservationAndReleaseRollbackWithTheOutbox() {
        Listing listing = listing(false);
        var reservation = reservations.reserve(listing.getId(), "res-a", listing.getVersion());
        long before = outbox.count();
        doThrow(new IllegalStateException("outbox unavailable")).when(events).record(any(), any(), any(), any());
        assertThatThrownBy(() -> reservations.release(listing.getId(), reservation.reservationId())).isInstanceOf(IllegalStateException.class);
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.SOLD);
        Listing other = listing(false);
        assertThatThrownBy(() -> reservations.reserve(other.getId(), "res-b", other.getVersion())).isInstanceOf(IllegalStateException.class);
        assertThat(listings.findById(other.getId()).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(outbox.count()).isEqualTo(before);
    }

    @Test
    void malformedReleaseIsDeadLettered() {
        String payload = "{\"reservationId\":\"res-orphan\"}";
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.ORDER_CANCELLED, payload);
        Message rejected = rabbit.receive(RabbitConfig.ORDER_LIFECYCLE_DLQ, 10000);
        assertThat(rejected).isNotNull();
        assertThat(new String(rejected.getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void cancellationWithoutReservationLeavesHoldIntact() {
        Listing listing = listing(false);
        assertThat(reserve(listing.getId(), "hold-keep", listing.getVersion()).outcome()).isEqualTo(Outcome.RESERVED);
        String payload = "{\"listingId\":\"" + listing.getId() + "\",\"listingType\":\"HARDWARE\","
                + "\"reservationId\":null,\"cancellationReason\":\"BUYER_ABORTED\"}";
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.ORDER_CANCELLED, payload);
        assertThat(rabbit.receive(RabbitConfig.ORDER_LIFECYCLE_DLQ, 3000)).isNull();
        Listing held = listings.findById(listing.getId()).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(held.getReservationId()).isEqualTo("hold-keep");
    }

    @Test
    void offerHoldReleaseReturnsListingAndFencesLateReserve() throws Exception {
        Listing listing = listing(false);
        assertThat(reserve(listing.getId(), "hold-release", listing.getVersion()).outcome()).isEqualTo(Outcome.RESERVED);
        String payload = offerHoldReleasePayload(listing.getId(), "hold-release");
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.OFFER_HOLD_RELEASED, payload);
        await(() -> listings.findById(listing.getId()).orElseThrow().getStatus() == ListingStatus.ACTIVE,
                "offer hold release");
        Listing released = listings.findById(listing.getId()).orElseThrow();

        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.OFFER_HOLD_RELEASED, payload);
        assertThat(rabbit.receive(RabbitConfig.ORDER_LIFECYCLE_DLQ, 3000)).isNull();
        Listing afterRedelivery = listings.findById(listing.getId()).orElseThrow();
        assertThat(afterRedelivery.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(afterRedelivery.getVersion()).isEqualTo(released.getVersion());
        assertThat(reserve(listing.getId(), "hold-release", afterRedelivery.getVersion()).outcome())
                .isEqualTo(Outcome.NOT_RESERVABLE);
    }

    @Test
    void offerHoldReleaseCannotUndoALaterSale() throws Exception {
        Listing listing = listing(false);
        ReserveListingResponse oldHold = reserve(listing.getId(), "hold-old", listing.getVersion());
        assertThat(oldHold.outcome()).isEqualTo(Outcome.RESERVED);
        publishRelease(listing, oldHold, RebyteExchanges.ORDER_CANCELLED);
        await(() -> listings.findById(listing.getId()).orElseThrow().getStatus() == ListingStatus.ACTIVE,
                "old hold release");
        Listing released = listings.findById(listing.getId()).orElseThrow();
        assertThat(reserve(listing.getId(), "sale-new", released.getVersion()).outcome()).isEqualTo(Outcome.RESERVED);

        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.OFFER_HOLD_RELEASED,
                offerHoldReleasePayload(listing.getId(), "hold-old"));
        assertThat(rabbit.receive(RabbitConfig.ORDER_LIFECYCLE_DLQ, 3000)).isNull();
        Listing sold = listings.findById(listing.getId()).orElseThrow();
        assertThat(sold.getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(sold.getReservationId()).isEqualTo("sale-new");
    }

    @Test
    void softwareCancellationIsAcknowledgedWithoutRelease() {
        String listingId = listing(true).getId();
        String payload = "{\"listingId\":\"" + listingId + "\",\"listingType\":\"SOFTWARE\",\"reservationId\":null,"
                + "\"cancellationReason\":\"TIMEOUT\"}";
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.ORDER_CANCELLED, payload);
        assertThat(rabbit.receive(RabbitConfig.ORDER_LIFECYCLE_DLQ, 3000)).isNull();
        assertThat(listings.findById(listingId).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    void generatedOpenApiIncludesPublicButNoInternalPaths() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint("docs-reader"));
        var response = http.exchange("/v3/api-docs", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var paths = mapper.readTree(response.getBody()).get("paths");
        assertThat(paths.has("/listings/hardware")).isTrue();
        assertThat(paths.properties()).noneMatch(entry -> entry.getKey().startsWith("/internal"));
    }

    private ReserveListingResponse reserve(String id, String reservationId, Long expectedVersion) {
        return http.exchange("/internal/listings/" + id + "/reserve", HttpMethod.PATCH,
                new HttpEntity<>(new ReserveListingRequest(reservationId, expectedVersion)), ReserveListingResponse.class).getBody();
    }
    private void publishRelease(Listing listing, ReserveListingResponse reservation, String key) throws Exception {
        rabbit.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, key,
                mapper.writeValueAsString(new OrderReleaseEvent(listing.getId(), reservation.reservationId(), Instant.now())));
    }
    private String offerHoldReleasePayload(String listingId, String reservationId) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "offerId", UUID.randomUUID().toString(),
                "listingId", listingId,
                "listingType", "HARDWARE",
                "reservationId", reservationId,
                "occurredAt", Instant.now()));
    }
    private Listing listing(boolean software) {
        String categoryId = UUID.randomUUID().toString();
        mongo.insert(new Category(categoryId, "Test leaf " + categoryId, null,
                List.of(software ? "Software" : "Hardware"),
                List.of(), List.of(), "admin", Instant.now(), 0L));
        return listings.save(new Listing(UUID.randomUUID().toString(), "probe" + UUID.randomUUID().toString().replace("-", ""),
                "Test listing", BigDecimal.TEN, Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.BANK_TRANSFER),
                "seller", categoryId, software ? null : new HardwareListing(Condition.GOOD, "Roma", BigDecimal.ZERO),
                software ? new Listing.SoftwareListing() : null, Map.of()));
    }
    private boolean searchContains(String title) {
        var result = http.getForObject("/search?q=" + title, SearchResultPage.class);
        return result != null && !result.hits().isEmpty();
    }
    private void await(BooleanSupplier condition, String description) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out: " + description);
    }
}
