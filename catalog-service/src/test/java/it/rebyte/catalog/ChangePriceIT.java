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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import it.rebyte.commons.messaging.RebyteExchanges;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class ChangePriceIT extends MongoTestBase {

    @Autowired TestRestTemplate http;
    @Autowired ListingRepository listings;
    @Autowired OutboxRepository outbox;
    @Autowired ObjectMapper mapper;
    @Autowired MongoTemplate mongo;

    @Test
    void successfulChangesAppendAChainedHistoryAndEmitBothPrices() {
        Listing listing = listing("seller", ListingStatus.ACTIVE, "10.00");

        assertThat(change("seller", listing.getId(), "10.01").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(change("seller", listing.getId(), "9.99").getStatusCode()).isEqualTo(HttpStatus.OK);

        Listing stored = mongo.findById(listing.getId(), Listing.class);
        assertThat(stored.getPrice()).isEqualByComparingTo("9.99");
        assertThat(stored.getPriceHistory()).hasSize(2);
        assertThat(stored.getPriceHistory().get(0).getOldPrice()).isEqualByComparingTo("10.00");
        assertThat(stored.getPriceHistory().get(0).getNewPrice()).isEqualByComparingTo("10.01");
        assertThat(stored.getPriceHistory().get(1).getOldPrice()).isEqualByComparingTo("10.01");
        assertThat(outbox.findAll()).filteredOn(event -> RebyteExchanges.LISTING_UPDATED.equals(event.getRoutingKey()))
                .hasSize(2).allSatisfy(event -> {
                    var payload = mapper.readTree(event.getPayload());
                    assertThat(payload.get("listingTitle")).isNotNull();
                    assertThat(payload.get("listingTitle").asText()).isEqualTo(listing.getTitle());
                    var change = stored.getPriceHistory().stream()
                            .filter(entry -> entry.getNewPrice().compareTo(payload.get("newPrice").decimalValue()) == 0)
                            .findFirst().orElseThrow();
                    assertThat(payload.get("oldPrice").decimalValue()).isEqualByComparingTo(change.getOldPrice());
                    assertThat(Instant.parse(payload.get("occurredAt").asText())).isEqualTo(change.getInstant())
                            .isEqualTo(event.getOccurredAt());
                    assertThat(mapper.readValue(event.getPayload(), ListingChangedEvent.class).listingId())
                            .isEqualTo(listing.getId());
                });
    }

    @Test
    void nonPriceEventKeepsPricesAndTitleNull() throws Exception {
        var payload = mapper.readTree(mapper.writeValueAsString(new ListingChangedEvent("listing", 1, Instant.now())));
        assertThat(payload.get("listingTitle")).isNotNull();
        assertThat(payload.get("listingTitle").isNull()).isTrue();
        assertThat(payload.get("oldPrice").isNull()).isTrue();
        assertThat(payload.get("newPrice").isNull()).isTrue();
    }

    @Test
    void invalidPrecisionEqualPriceNonOwnerAndSoldListingAreRefused() {
        Listing active = listing("seller", ListingStatus.ACTIVE, "10.00");
        assertThat(change("seller", active.getId(), "10.001").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(change("seller", active.getId(), "10.0").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(change("intruder", active.getId(), "9.99").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Listing sold = listing("seller", ListingStatus.SOLD, "10.00");
        assertThat(change("seller", sold.getId(), "9.99").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(listings.findById(active.getId()).orElseThrow().getPriceHistory()).isEmpty();
    }

    private ResponseEntity<Map> change(String subject, String listingId, String price) {
        return http.exchange("/listings/" + listingId + "/price", HttpMethod.PATCH,
                authenticated(subject, Map.of("price", price)), Map.class);
    }

    private Listing listing(String seller, ListingStatus status, String price) {
        return listings.save(new Listing(UUID.randomUUID().toString(), "Listing", "Description",
                new BigDecimal(price), Instant.now(), status, List.of(), Set.of(PaymentMethod.PAYPAL), seller,
                "category", new HardwareListing(Condition.GOOD, "Rome", new BigDecimal("5.00")), null, Map.of()));
    }

    private HttpEntity<?> authenticated(String subject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
