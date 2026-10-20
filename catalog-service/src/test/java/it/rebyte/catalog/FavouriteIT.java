package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;

import it.rebyte.catalog.domain.*;
import it.rebyte.catalog.dto.FavouriteListingStatus;
import it.rebyte.catalog.error.FavouriteNotFoundException;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.outbox.OutboxEvent;
import it.rebyte.catalog.repository.FavouriteRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.service.FavouriteService;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.commons.messaging.RebyteExchanges;

@SpringBootTest
class FavouriteIT extends MongoTestBase {
    @Autowired FavouriteService service;
    @Autowired FavouriteRepository favourites;
    @Autowired ListingRepository listings;
    @Autowired OutboxRepository outbox;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean DomainEventRecorder events;

    @Test void favouritesReturnPhotoUrlsAndTheFirstPhotoAsCover() {
        Listing listing = listing();
        List<String> photoKeys = List.of("listings/" + listing.getId() + "/a", "listings/" + listing.getId() + "/b");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(listing.getId())),
                new Update().set("photos", photoKeys), Listing.class);
        String buyer = UUID.randomUUID().toString();
        service.add(buyer, listing.getId());

        assertThat(service.list(buyer, 0, 20).favourites()).singleElement().satisfies(entry -> {
            assertThat(entry.photos()).containsExactly(
                    "https://cdn.example.test/listings/" + listing.getId() + "/a",
                    "https://cdn.example.test/listings/" + listing.getId() + "/b");
            assertThat(entry.coverUrl()).isEqualTo(entry.photos().getFirst());
            assertThat(entry.status()).isEqualTo(FavouriteListingStatus.AVAILABLE);
        });
        assertThat(listings.findById(listing.getId()).orElseThrow().getPhotos()).containsExactlyElementsOf(photoKeys);
    }

    @Test void favouriteWithoutPhotosHasNoCover() {
        Listing listing = listing();
        String buyer = UUID.randomUUID().toString();
        service.add(buyer, listing.getId());

        assertThat(service.list(buyer, 0, 20).favourites()).singleElement().satisfies(entry -> {
            assertThat(entry.photos()).isEmpty();
            assertThat(entry.coverUrl()).isNull();
        });
    }

    @Test void favouriteWhoseListingIsGoneHasNoCoverAndRetainsUnavailableFallback() {
        Listing listing = listing();
        String buyer = UUID.randomUUID().toString();
        service.add(buyer, listing.getId());
        listings.deleteById(listing.getId());

        assertThat(service.list(buyer, 0, 20).favourites()).singleElement().satisfies(entry -> {
            assertThat(entry.listingId()).isEqualTo(listing.getId());
            assertThat(entry.title()).isEqualTo("Removed listing");
            assertThat(entry.price()).isNull();
            assertThat(entry.photos()).isEmpty();
            assertThat(entry.coverUrl()).isNull();
            assertThat(entry.status()).isEqualTo(FavouriteListingStatus.UNAVAILABLE);
        });
    }

    @Test void transitionsHaveMatchingIdentityTimeAndRetainedSequence() throws Exception {
        Listing listing = listing();
        String buyer = UUID.randomUUID().toString();
        Favourite added = service.add(buyer, listing.getId());
        Favourite stored = favourites.findById(added.getId()).orElseThrow();
        OutboxEvent first = event(listing, RebyteExchanges.LISTING_FAVOURITED);
        JsonNode addPayload = assertPayload(first, listing, buyer);
        assertThat(Instant.parse(addPayload.get("occurredAt").asText())).isEqualTo(stored.getCreatedAt());
        assertThat(added.getCreatedAt()).isEqualTo(stored.getCreatedAt());
        assertThat(addPayload.get("relationshipSequence").asLong()).isEqualTo(1);

        service.add(buyer, listing.getId());
        assertThat(clockSequence(added.getId())).isEqualTo(1);
        service.remove(buyer, listing.getId());
        JsonNode removePayload = assertPayload(event(listing, RebyteExchanges.LISTING_UNFAVOURITED), listing, buyer);
        assertThat(Instant.parse(removePayload.get("occurredAt").asText())).isAfterOrEqualTo(stored.getCreatedAt());
        assertThat(removePayload.get("eventId").asText()).isNotEqualTo(first.getEventId());
        assertThat(removePayload.get("relationshipSequence").asLong()).isEqualTo(2);
        assertThatThrownBy(() -> service.remove(buyer, listing.getId())).isInstanceOf(FavouriteNotFoundException.class);
        assertThat(clockSequence(added.getId())).isEqualTo(2);
        service.add(buyer, listing.getId());
        assertThat(clockSequence(added.getId())).isEqualTo(3);
        assertThat(outbox.findAll()).filteredOn(e -> e.getPayload().contains(listing.getId())).hasSize(3)
                .allSatisfy(e -> assertThat(mapper.readTree(e.getPayload()).get("relationshipSequence").asLong())
                        .isBetween(1L, 3L));
    }

    @Test void failedTransitionRollsBackClockFavouriteAndEvent() {
        Listing listing = listing();
        String buyer = UUID.randomUUID().toString();
        Favourite added = service.add(buyer, listing.getId());
        doThrow(new IllegalStateException("outbox unavailable")).when(events).record(any(), any(), any(), any());
        try {
            assertThatThrownBy(() -> service.remove(buyer, listing.getId())).isInstanceOf(IllegalStateException.class);
            assertThat(clockSequence(added.getId())).isEqualTo(1);
            assertThat(favourites.findById(added.getId())).isPresent();
            assertThat(outbox.findAll()).filteredOn(e -> e.getPayload().contains(listing.getId())).hasSize(1);
            String otherBuyer = UUID.randomUUID().toString();
            assertThatThrownBy(() -> service.add(otherBuyer, listing.getId())).isInstanceOf(IllegalStateException.class);
            FavouriteId otherId = new FavouriteId(otherBuyer, listing.getId());
            assertThat(favourites.findById(otherId)).isEmpty();
            assertThat(clockSequence(otherId)).isNull();
        } finally {
            reset(events);
        }
    }

    @Test void removalAfterListingDeletionKeepsSellerAndAllowsNullTitle() throws Exception {
        Listing listing = listing();
        String buyer = UUID.randomUUID().toString();
        service.add(buyer, listing.getId());
        listings.deleteById(listing.getId());
        service.remove(buyer, listing.getId());
        JsonNode payload = mapper.readTree(event(listing, RebyteExchanges.LISTING_UNFAVOURITED).getPayload());
        assertThat(payload.get("sellerSub").asText()).isEqualTo(listing.getSellerSub());
        assertThat(payload.get("listingTitle").isNull()).isTrue();
        assertThat(payload.get("relationshipSequence").asLong()).isEqualTo(2);
    }

    private Long clockSequence(FavouriteId id) {
        Document clock = mongo.findOne(Query.query(Criteria.where("_id").is(id)), Document.class,
                "favourite_transition_clock");
        return clock == null ? null : ((Number) clock.get("sequence")).longValue();
    }

    private OutboxEvent event(Listing listing, String routingKey) {
        return outbox.findAll().stream().filter(e -> e.getPayload().contains(listing.getId()))
                .filter(e -> routingKey.equals(e.getRoutingKey())).findFirst().orElseThrow();
    }

    private JsonNode assertPayload(OutboxEvent event, Listing listing, String buyer) throws Exception {
        JsonNode payload = mapper.readTree(event.getPayload());
        Set<String> keys = new java.util.HashSet<>();
        payload.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("eventId", "userSub", "listingId", "sellerSub",
                "listingTitle", "occurredAt", "relationshipSequence");
        assertThat(payload.get("eventId").asText()).isEqualTo(event.getEventId());
        assertThat(payload.get("userSub").asText()).isEqualTo(buyer);
        assertThat(payload.get("listingId").asText()).isEqualTo(listing.getId());
        assertThat(payload.get("sellerSub").asText()).isEqualTo(listing.getSellerSub());
        assertThat(payload.get("listingTitle").asText()).isEqualTo(listing.getTitle());
        assertThat(Instant.parse(payload.get("occurredAt").asText())).isEqualTo(event.getOccurredAt());
        return payload;
    }

    @Test void repeatedAddIsOneRowAndOneEvent() {
        Listing listing = listing();
        service.add("buyer", listing.getId());
        service.add("buyer", listing.getId());
        assertThat(favourites.findByIdUserSub("buyer")).hasSize(1);
        assertThat(outbox.findAll()).filteredOn(e -> e.getPayload().contains(listing.getId()))
                .filteredOn(e -> RebyteExchanges.LISTING_FAVOURITED.equals(e.getRoutingKey())).hasSize(1);
    }

    @Test void removingAbsentFavouriteEmitsNoEvent() {
        Listing listing = listing();

        assertThatThrownBy(() -> service.remove("buyer", listing.getId()))
                .isInstanceOf(FavouriteNotFoundException.class);
        assertThat(outbox.findAll()).filteredOn(e -> e.getPayload().contains(listing.getId()))
                .filteredOn(e -> RebyteExchanges.LISTING_UNFAVOURITED.equals(e.getRoutingKey()))
                .isEmpty();
    }

    @Test void concurrentRemoveDeletesAndEmitsOnce() throws Exception {
        Listing listing = listing();
        service.add("buyer", listing.getId());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); remove(listing); return null; });
            var b = pool.submit(() -> { start.await(); remove(listing); return null; });
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        }
        assertThat(favourites.findByIdUserSub("buyer")).isEmpty();
        assertThat(outbox.findAll()).filteredOn(e -> RebyteExchanges.LISTING_UNFAVOURITED.equals(e.getRoutingKey())).hasSize(1);
    }

    private void remove(Listing listing) {
        try { service.remove("buyer", listing.getId()); }
        catch (FavouriteNotFoundException expected) { }
    }

    private Listing listing() {
        return listings.save(new Listing(UUID.randomUUID().toString(), "Listing", "Description", BigDecimal.TEN,
                Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL), "seller", "category",
                new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, Map.of()));
    }
}
