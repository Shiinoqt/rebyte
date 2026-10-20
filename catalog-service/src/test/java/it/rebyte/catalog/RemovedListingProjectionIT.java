package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.context.ActiveProfiles;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.catalog.outbox.OutboxRelay;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.FavouriteRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.service.FavouriteService;
import it.rebyte.catalog.service.ListingRemovalService;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import it.rebyte.commons.messaging.RebyteExchanges;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class RemovedListingProjectionIT extends CatalogInfraTestBase {
    @Autowired TestRestTemplate restTemplate;
    @Autowired FavouriteService favourites;
    @Autowired ListingRemovalService removal;
    @Autowired FavouriteRepository favouriteRepository;
    @Autowired ListingRepository listings;
    @Autowired CategoryRepository categories;
    @Autowired DomainEventRecorder events;
    @Autowired OutboxRelay relay;
    @Autowired OutboxRepository outbox;

    @Test void removalDeletesSearchProjectionButKeepsFavouriteAndHidesListing() {
        String seller = "seller-" + UUID.randomUUID();
        String buyer = "buyer-" + UUID.randomUUID();
        Listing listing = listing(seller);
        ResponseEntity<String> beforeRemoval = restTemplate.exchange("/listings/" + listing.getId(), HttpMethod.GET,
                auth("other-before-removal"), String.class);
        assertThat(beforeRemoval.getStatusCode().value()).isEqualTo(200);
        assertThat(beforeRemoval.getBody()).doesNotContain("\"moderation\"");
        indexCreatedListing(listing);
        favourites.add(buyer, listing.getId());
        removal.remove("admin-1", true, listing.getId(), "offensive");
        relay.drainUntilEmptyOrCapped();

        assertThat(favouriteRepository.findByIdUserSub(buyer)).hasSize(1);
        assertThat(outbox.findAll()).filteredOn(e -> e.getPayload().contains(listing.getId()))
                .filteredOn(e -> RebyteExchanges.LISTING_UNFAVOURITED.equals(e.getRoutingKey())).isEmpty();
        pollSearch(listing.getTitle(), false);
        assertThat(restTemplate.getForEntity("/listings/" + listing.getId(), String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(restTemplate.exchange("/listings/" + listing.getId(), HttpMethod.GET,
                auth("other-" + UUID.randomUUID()), String.class).getStatusCode().value()).isEqualTo(404);
        ResponseEntity<String> sellerDetail = restTemplate.exchange("/listings/" + listing.getId(), HttpMethod.GET,
                auth(seller), String.class);
        assertThat(sellerDetail.getStatusCode().value()).isEqualTo(200);
        assertThat(sellerDetail.getBody()).contains("\"moderation\":{", "offensive");
        ResponseEntity<String> buyerFavourites = restTemplate.exchange("/favourites", HttpMethod.GET, auth(buyer), String.class);
        assertThat(buyerFavourites.getStatusCode().value()).isEqualTo(200);
        assertThat(buyerFavourites.getBody()).contains(listing.getId(), "UNAVAILABLE", listing.getTitle());
        ResponseEntity<String> reFavourite = restTemplate.exchange("/favourites/" + listing.getId(), HttpMethod.PUT,
                auth(buyer), String.class);
        assertThat(reFavourite.getStatusCode().is4xxClientError()).isTrue();
        assertThat(favouriteRepository.findByIdUserSub(buyer)).hasSize(1);
    }

    private void indexCreatedListing(Listing listing) {
        events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_CREATED,
                new ListingChangedEvent(listing.getId(), listing.getVersion(), Instant.now()), Instant.now());
        relay.drainUntilEmptyOrCapped();
        pollSearch(listing.getTitle(), true);
    }

    private Listing listing(String seller) {
        Category category = categories.save(new Category(UUID.randomUUID().toString(), "GPU-" + UUID.randomUUID(), null,
                List.of("Hardware"), List.of(), List.of(), "admin", Instant.now(), 0L));
        String title = "RemovedProjection-" + UUID.randomUUID();
        return listings.save(new Listing(UUID.randomUUID().toString(), title, "Description", BigDecimal.TEN, Instant.now(),
                ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL), seller, category.getId(),
                new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, Map.of()));
    }

    private HttpEntity<Void> auth(String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint(sub));
        return new HttpEntity<>(headers);
    }

    private void pollSearch(String query, boolean expectedHit) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            String body = restTemplate.getForEntity("/search?q=" + query, String.class).getBody();
            boolean hit = body != null && body.contains(query);
            if (hit == expectedHit) return;
            try { Thread.sleep(250); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("search projection did not reach expected state for " + query);
    }
}
