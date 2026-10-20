package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Index;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.outbox.OutboxRelay;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.dto.SearchHit;
import it.rebyte.catalog.search.ListingIndexer;
import it.rebyte.catalog.search.ListingSearchService;
import it.rebyte.catalog.service.CategoryTreeCache;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import it.rebyte.commons.messaging.RebyteExchanges;

@SpringBootTest
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
@ExtendWith(OutputCaptureExtension.class)
class SearchProjectionIT extends CatalogInfraTestBase {

    @Autowired ListingRepository listings;
    @Autowired CategoryRepository categories;
    @Autowired CategoryTreeCache categoryTreeCache;
    @Autowired ListingIndexer indexer;
    @Autowired Index index;
    @Autowired ObjectMapper mapper;
    @Autowired DomainEventRecorder events;
    @Autowired OutboxRelay relay;
    @Autowired ListingSearchService searchService;
    @Autowired MongoTemplate mongo;

    @Test
    void publishedProjectionLatencyIsMeasuredAndStaleRedeliveryIsDiscarded(CapturedOutput output) throws Exception {
        String id = "projection-" + UUID.randomUUID();
        Category category = categories.save(new Category("category-" + UUID.randomUUID(), "Projection leaf " + id,
                null, List.of("Hardware"), List.of(), List.of(), "admin", Instant.now(), 0L));
        categoryTreeCache.evict();
        Listing listing = listings.save(new Listing(id, "Projection fixture", "fixture", BigDecimal.TEN,
                Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.BANK_TRANSFER), "seller",
                category.getId(), new HardwareListing(Condition.GOOD, "Torino", BigDecimal.ZERO), null, Map.of()));

        Instant started = Instant.now();
        events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_CREATED,
                new ListingChangedEvent(id, listing.getVersion(), started), started);
        relay.drainUntilEmptyOrCapped();
        String indexed = awaitDocument(id, Duration.ofSeconds(5));
        Duration latency = Duration.between(started, Instant.now());
        assertThat(latency).isLessThan(Duration.ofSeconds(5));
        assertThat(mapper.readTree(indexed).path("id").asText()).isEqualTo(id);

        String before = indexed;
        indexer.apply(new ListingChangedEvent(id, listing.getVersion(), Instant.now()),
                RebyteExchanges.LISTING_UPDATED, index);
        assertThat(index.getRawDocument(id)).isEqualTo(before);
        assertThat(output).contains("DISCARDED_STALE listingId=" + id);
    }

    @Test
    void hitsCarryTheCdnUrlOfTheFirstPhotoAndNeverTheStoredKey() throws Exception {
        String token = "cover" + UUID.randomUUID().toString().replace("-", "");
        String bareToken = "plain" + UUID.randomUUID().toString().replace("-", "");
        Category category = categories.save(new Category("category-" + UUID.randomUUID(), "Cover leaf " + token,
                null, List.of("Hardware"), List.of(), List.of(), "admin", Instant.now(), 0L));
        categoryTreeCache.evict();
        Listing withPhotos = listings.save(coverListing(token, category, List.of("k1", "k2")));
        Listing withoutPhotos = listings.save(coverListing(bareToken, category, List.of()));
        project(withPhotos.getId(), withPhotos.getVersion());
        project(withoutPhotos.getId(), withoutPhotos.getVersion());

        assertThat(awaitHit(token, "https://cdn.example.test/k1", Duration.ofSeconds(5)).coverUrl())
                .isEqualTo("https://cdn.example.test/k1");
        assertThat(index.getRawDocument(withPhotos.getId())).contains("\"coverPhotoKey\":\"k1\"");
        assertThat(awaitHit(bareToken, null, Duration.ofSeconds(5)).coverUrl()).isNull();

        mongo.updateFirst(Query.query(where("id").is(withPhotos.getId())),
                new Update().set("photos", List.of("k2", "k1")).inc("version", 1L), Listing.class);
        project(withPhotos.getId(), withPhotos.getVersion() + 1L);

        assertThat(awaitHit(token, "https://cdn.example.test/k2", Duration.ofSeconds(5)).coverUrl())
                .isEqualTo("https://cdn.example.test/k2");
        String hitsJson = mapper.writeValueAsString(searchService.search(token).hits());
        assertThat(hitsJson).doesNotContain("coverPhotoKey").contains("\"coverUrl\"");
    }

    private Listing coverListing(String title, Category category, List<String> photos) {
        return new Listing(UUID.randomUUID().toString(), title, "fixture", BigDecimal.TEN,
                Instant.now(), ListingStatus.ACTIVE, photos, Set.of(PaymentMethod.BANK_TRANSFER), "seller",
                category.getId(), new HardwareListing(Condition.GOOD, "Torino", BigDecimal.ZERO), null, Map.of());
    }

    private void project(String listingId, long seq) {
        Instant now = Instant.now();
        events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_UPDATED,
                new ListingChangedEvent(listingId, seq, now), now);
        relay.drainUntilEmptyOrCapped();
    }

    private SearchHit awaitHit(String title, String expectedCoverUrl, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        SearchHit last = null;
        while (Instant.now().isBefore(deadline)) {
            var hits = searchService.search(title).hits();
            if (!hits.isEmpty()) {
                last = hits.getFirst();
                if (java.util.Objects.equals(last.coverUrl(), expectedCoverUrl)) {
                    return last;
                }
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError("hit for " + title + " never reached coverUrl " + expectedCoverUrl + ", last=" + last);
    }

    private String awaitDocument(String id, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Exception lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                return index.getRawDocument(id);
            } catch (Exception e) {
                lastFailure = e;
                LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
            }
        }
        throw new AssertionError("listing was not projected through RabbitMQ within " + timeout, lastFailure);
    }
}
