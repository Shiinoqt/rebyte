package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.SearchResult;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.search.IndexDriftCheckJob;
import it.rebyte.catalog.search.IndexRebuildJob;
import it.rebyte.catalog.search.ListingDocumentAssembler;
import it.rebyte.catalog.search.ListingDocument;
import it.rebyte.catalog.search.ListingIndexer;
import it.rebyte.catalog.dto.RebuildStatusResponse;
import it.rebyte.catalog.dto.SearchHit;
import it.rebyte.catalog.search.ListingSearchService;
import it.rebyte.catalog.service.CategoryTreeCache;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import it.rebyte.commons.messaging.RebyteExchanges;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
@TestPropertySource(properties = "rebyte.search.rebuild.batch-size=1")
class IndexRebuildSwapIT extends CatalogInfraTestBase {

    @Autowired ListingRepository listings;
    @Autowired CategoryRepository categories;
    @Autowired CategoryTreeCache categoryTreeCache;
    @Autowired ListingDocumentAssembler assembler;
    @Autowired Index liveIndex;
    @Autowired ObjectMapper mapper;
    @Autowired IndexRebuildJob rebuild;
    @Autowired IndexDriftCheckJob drift;
    @Autowired MongoTemplate mongo;
    @Autowired DomainEventRecorder events;
    @Autowired ListingIndexer indexer;
    @Autowired ListingSearchService searchService;
    @Autowired TestRestTemplate http;

    @Test
    void rebuildSwapsOnlyCompleteIndexesReplaysConcurrentUpdateAndReportsDrift() throws Exception {
        String token = "rebuild" + UUID.randomUUID().toString().replace("-", "");
        Category category = categories.save(new Category(token + "-category", token + " category", null,
                List.of(token + " root"), List.of(), List.of(), "admin", Instant.now(), 0L));
        categoryTreeCache.evict();

        List<Listing> saved = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            List<String> photos = index == 4 ? List.of("cover-1", "cover-2") : List.of();
            saved.add(listings.save(listing(token + "-" + index, category.getId(), new BigDecimal("10.00"), photos)));
        }
        // Documents written before the cover field existed carry no coverPhotoKey at all.
        List<Map<String, Object>> legacyDocuments = saved.stream()
                .map(listing -> {
                    Map<String, Object> document = mapper.convertValue(
                            assembler.assemble(listing, listing.getVersion()), new TypeReference<>() { });
                    document.remove("coverPhotoKey");
                    return document;
                })
                .toList();
        var initialTask = liveIndex.addDocuments(mapper.writeValueAsString(legacyDocuments));
        liveIndex.waitForTask(initialTask.getTaskUid());

        assertThat(http.getForEntity("/admin/search/rebuild", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(TestJwtSupport.mint("search-admin", List.of("ADMIN")));
        var trigger = http.exchange("/admin/search/rebuild", HttpMethod.POST,
                new HttpEntity<Void>(adminHeaders), RebuildStatusResponse.class);
        assertThat(trigger.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(trigger.getBody()).isNotNull();
        assertThat(trigger.getBody().running()).isTrue();
        assertThat(rebuild.start()).as("a concurrent rebuild must be refused").isFalse();

        var runningStatus = http.exchange("/admin/search/rebuild", HttpMethod.GET,
                new HttpEntity<Void>(adminHeaders), RebuildStatusResponse.class);
        assertThat(runningStatus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runningStatus.getBody()).isNotNull();
        assertThat(runningStatus.getBody().running()).isTrue();

        Listing changed = saved.getFirst();
        mongo.updateFirst(Query.query(where("id").is(changed.getId())),
                new Update().set("price", new BigDecimal("99.00")).inc("version", 1L), Listing.class);
        Instant changedAt = Instant.now();
        events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_UPDATED,
                new ListingChangedEvent(changed.getId(), changed.getVersion() + 1L, changedAt), changedAt);

        List<Integer> visibleCounts = new ArrayList<>();
        while (rebuild.status().running()) {
            SearchResult result = (SearchResult) liveIndex.search(SearchRequest.builder()
                    .filter(new String[] {"status = ACTIVE"}).limit(100).build());
            visibleCounts.add(result.getEstimatedTotalHits());
            Thread.onSpinWait();
        }

        assertThat(rebuild.status().error()).isNull();
        assertThat(visibleCounts).isNotEmpty().allMatch(count -> count == 10);
        assertThat(mapper.readTree(liveIndex.getRawDocument(changed.getId())).path("price").decimalValue())
                .isEqualByComparingTo("99.00");

        String afterRebuild = liveIndex.getRawDocument(changed.getId());
        indexer.apply(new ListingChangedEvent(changed.getId(), changed.getVersion(), Instant.now()),
                RebyteExchanges.LISTING_UPDATED, liveIndex);
        assertThat(liveIndex.getRawDocument(changed.getId())).isEqualTo(afterRebuild);

        Map<String, String> coverUrls = searchService.search(token).hits().stream()
                .collect(java.util.stream.Collectors.toMap(SearchHit::id,
                        hit -> String.valueOf(hit.coverUrl())));
        assertThat(coverUrls).hasSize(10);
        assertThat(coverUrls.get(saved.get(4).getId())).isEqualTo("https://cdn.example.test/cover-1");
        assertThat(coverUrls.get(saved.getFirst().getId())).isEqualTo("null");

        assertThat(drift.check().total()).isZero();
        var delete = liveIndex.deleteDocument(saved.get(1).getId());
        liveIndex.waitForTask(delete.getTaskUid());
        assertThat(drift.check().missingFromIndex()).contains(saved.get(1).getId());

        ListingDocument orphan = assembler.assemble(saved.get(2), saved.get(2).getVersion());
        var orphanTask = liveIndex.addDocuments(mapper.writeValueAsString(List.of(
                new ListingDocument(
                        token + "-orphan", orphan.title(), orphan.description(), orphan.price(), orphan.listingType(),
                        orphan.status(), orphan.categoryId(), orphan.categoryPathIds(), orphan.categoryPath(),
                        orphan.sellerSub(), orphan.sellerUsername(), orphan.publishedAt(), orphan.condition(),
                        orphan.shippingLocation(), orphan.platforms(), orphan.currentVersion(), orphan.coverPhotoKey(), orphan.seq(), orphan.attr()))));
        liveIndex.waitForTask(orphanTask.getTaskUid());
        assertThat(drift.check().missingFromSource()).contains(token + "-orphan");

        mongo.updateFirst(Query.query(where("id").is(saved.get(3).getId())),
                new Update().inc("version", 1L), Listing.class);
        assertThat(drift.check().staleDocuments()).containsKey(saved.get(3).getId());
    }

    private Listing listing(String id, String categoryId, BigDecimal price, List<String> photos) {
        return new Listing(id, "Rebuild fixture " + id, "fixture", price, Instant.now(), ListingStatus.ACTIVE,
                photos, Set.of(PaymentMethod.BANK_TRANSFER), "seller", categoryId,
                new HardwareListing(Condition.GOOD, "Torino", BigDecimal.ZERO), null, Map.of());
    }
}
