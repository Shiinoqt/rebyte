package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.dto.SearchHit;
import it.rebyte.catalog.dto.SearchResultPage;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.outbox.OutboxRelay;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import it.rebyte.commons.messaging.RebyteExchanges;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * SRCH-01's empty and encoding edges (02-01-PLAN.md Task 2), plus the
 * status-filter edge from Task 2's acceptance criteria: a {@code SOLD}
 * listing must stop appearing in default search once reindexed.
 *
 * <p>Every test uses a unique, disposable query word embedded in the
 * listing's title as its search term — {@link CatalogInfraTestBase}'s
 * Meilisearch and Mongo containers are shared (static, JUnit's inherited-container
 * idiom) across every {@code *IT} class in the same JVM run, so asserting a
 * globally empty result set would be flaky against listings other test
 * classes left behind. Scoping every query to a word only this test's
 * fixture can contain keeps each assertion meaningful regardless of what
 * else has been indexed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class SearchTextMatchingIT extends CatalogInfraTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private DomainEventRecorder domainEventRecorder;

    @Autowired
    private OutboxRelay outboxRelay;

    @Test
    void blankQueryReturnsTheActiveListing() {
        String uniqueWord = uniqueWord("Blankq");
        String listingId = publishAndIndex(uniqueWord).id();

        SearchResultPage page = pollUntilResponse(response ->
                response.hits().stream().anyMatch(hit -> hit.id().equals(listingId)));

        assertThat(page.hits()).anySatisfy(hit -> assertThat(hit.id()).isEqualTo(listingId));
    }

    @Test
    void lowercaseUnaccentedQueryMatchesCapitalizedAccentedTitle() {
        String uniqueWord = uniqueWord("Étrange");
        String listingId = publishAndIndex(uniqueWord).id();

        String digitsSuffix = uniqueWord.replaceAll("[^0-9]", "");
        String lowercaseUnaccentedQuery = "etrange" + digitsSuffix;
        SearchResultPage page = pollUntilQueryReturnsAHit(lowercaseUnaccentedQuery);

        assertThat(page.hits()).anySatisfy(hit -> assertThat(hit.id()).isEqualTo(listingId));
    }

    @Test
    void soldListingReindexedIsExcludedFromDefaultSearch() {
        String uniqueWord = uniqueWord("Solditem");
        PublishedFixture published = publishAndIndex(uniqueWord);

        // "directly in Mongo" — a raw field update, bypassing the service
        // layer and its optimistic-locking write path entirely.
        mongoTemplate.updateFirst(
                Query.query(where("id").is(published.id())),
                Update.update("status", ListingStatus.SOLD),
                Listing.class);

        // "reindexed" — the same production path a real edit would take:
        // record a listing.updated event with a strictly greater sequence,
        // then drain the outbox exactly like the tracer does.
        domainEventRecorder.record(
                UUID.randomUUID().toString(),
                RebyteExchanges.LISTING_UPDATED,
                new ListingChangedEvent(published.id(), published.seq() + 1, Instant.now()),
                Instant.now());
        outboxRelay.drainUntilEmptyOrCapped();

        // Scoped to this fixture's own unique word, not a blank query: the
        // container is shared across every *IT class in this JVM run (see
        // class Javadoc), so other tests' still-ACTIVE listings would make a
        // blank-query "isEmpty()" assertion flaky. A query only this
        // listing's title could ever match makes "empty hit list" exact.
        SearchResultPage page = pollUntilResponse(uniqueWord, response -> response.hits().isEmpty());

        assertThat(page.hits()).isEmpty();
    }

    private record PublishedFixture(String id, long seq) {
    }

    private PublishedFixture publishAndIndex(String uniqueTitleWord) {
        // Plan 02-02 Task 1 added the compound unique index on
        // (parentId, name) — a fixed "GPU" name here collided across this
        // class's three test methods, which all share the same Testcontainers
        // Mongo instance (see class Javadoc). A per-call unique name keeps
        // this fixture independent of that new invariant.
        Category category = categoryRepository.save(new Category(
                UUID.randomUUID().toString(), "GPU-" + UUID.randomUUID(), null, List.of("Hardware"),
                List.of(), List.of(), "test-admin", Instant.now(), 0L));

        String sellerSub = UUID.randomUUID().toString();
        String token = TestJwtSupport.mint(sellerSub);

        Map<String, Object> body = new HashMap<>();
        body.put("title", uniqueTitleWord + " listing");
        body.put("description", "fixture for SearchTextMatchingIT");
        body.put("price", "100.00");
        body.put("categoryId", category.getId());
        body.put("photos", List.of());
        body.put("paymentMethods", List.of("BANK_TRANSFER"));
        body.put("condition", "GOOD");
        body.put("shippingLocation", "Torino");
        body.put("shippingCost", "5.00");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> publishResponse =
                restTemplate.exchange("/listings/hardware", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String listingId = (String) publishResponse.getBody().get("id");

        outboxRelay.drainUntilEmptyOrCapped();
        pollUntilQueryReturnsAHit(uniqueTitleWord);

        return new PublishedFixture(listingId, 0L);
    }

    /**
     * A digits-only suffix, deliberately — not a hex slice of a UUID. Digits
     * are case- and diacritic-invariant, so {@code
     * lowercaseUnaccentedQueryMatchesCapitalizedAccentedTitle} can rebuild
     * an exact prefix of the indexed token by concatenation alone. An
     * earlier version stripped non-digit characters OUT of a hex UUID slice
     * after the fact, which silently broke prefix contiguity whenever a hex
     * letter (a-f) landed between two digits — caught via genuine RED
     * running this IT against a real Meilisearch container, not assumed.
     */
    private String uniqueWord(String prefix) {
        return prefix + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
    }

    private SearchResultPage pollUntilQueryReturnsAHit(String query) {
        return pollUntilResponse(query, response -> !response.hits().isEmpty());
    }

    private SearchResultPage pollUntilResponse(java.util.function.Predicate<SearchResultPage> condition) {
        return pollUntilResponse("", condition);
    }

    private SearchResultPage pollUntilResponse(String query, java.util.function.Predicate<SearchResultPage> condition) {
        Instant deadline = Instant.now().plusSeconds(30);
        String path = query.isBlank() ? "/search" : "/search?q=" + query;
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<SearchResultPage> response = restTemplate.getForEntity(path, SearchResultPage.class);
            SearchResultPage body = response.getBody();
            if (body != null && condition.test(body)) {
                return body;
            }
            sleep();
        }
        throw new AssertionError("condition never became true for query \"" + query + "\" within the polling timeout");
    }

    private void sleep() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
