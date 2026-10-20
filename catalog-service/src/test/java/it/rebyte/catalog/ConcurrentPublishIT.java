package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

/**
 * 2026-09-23 folded todo (D-39): 320 of 5,000 concurrent {@code POST
 * /listings/hardware} requests returned a bare 500 under real contention.
 * {@code ListingPublishService}'s leaf-guard {@code findAndModify} on the
 * shared category document is the mirror image of {@code
 * CategoryAdminIT}'s sibling-race scenario for categories — this proves the
 * same class of MongoDB {@code WriteConflict} against the publish path,
 * barrier-started so every request truly overlaps rather than merely being
 * submitted close together.
 *
 * <p>Only {@link MongoTestBase} — no Rabbit or Meilisearch container
 * needed: nothing here drains the outbox or reads back from search, only
 * the synchronous HTTP response and the persisted {@code Listing} count
 * matter.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class ConcurrentPublishIT extends MongoTestBase {

    private static final int CONCURRENT_REQUESTS = 8;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Test
    void eightBarrierStartedConcurrentPublishesIntoOneLeafNeverReturn500() throws Exception {
        Category leaf = leafCategory();
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENT_REQUESTS);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        try {
            List<CompletableFuture<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                String uniqueTitleWord = "ConcurrentProbe" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    awaitBarrier(barrier);
                    return publish(leaf.getId(), uniqueTitleWord, "450.00", "10.00");
                }, pool));
            }
            List<ResponseEntity<Map>> responses = futures.stream().map(CompletableFuture::join).toList();

            assertThat(responses)
                    .as("no request may ever surface a bare 500 under contention")
                    .allSatisfy(response -> assertThat(response.getStatusCode().is5xxServerError()).isFalse());

            long created = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
            long documentedConflict = responses.stream()
                    .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT
                            || r.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT)
                    .count();
            assertThat(created + documentedConflict)
                    .as("every response is either a success or a documented 409/422 conflict")
                    .isEqualTo(CONCURRENT_REQUESTS);
            assertThat(created).as("the leaf guard must not reject every single concurrent request").isGreaterThan(0);

            long persistedForCreatedResponses = responses.stream()
                    .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                    .map(r -> (String) r.getBody().get("id"))
                    .filter(id -> listingRepository.findById(id).isPresent())
                    .count();
            assertThat(persistedForCreatedResponses)
                    .as("every 201 response must have a matching persisted listing")
                    .isEqualTo(created);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void priceWithThreeDecimalPlacesReturns400AndWritesNoListing() {
        Category leaf = leafCategory();
        long before = listingRepository.count();

        ResponseEntity<Map> response = publish(leaf.getId(), "ScalePriceProbe-" + UUID.randomUUID(), "450.001", "10.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(listingRepository.count()).isEqualTo(before);
    }

    @Test
    void shippingCostWithThreeDecimalPlacesReturns400AndWritesNoListing() {
        Category leaf = leafCategory();
        long before = listingRepository.count();

        ResponseEntity<Map> response =
                publish(leaf.getId(), "ScaleShippingProbe-" + UUID.randomUUID(), "450.00", "10.001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(listingRepository.count()).isEqualTo(before);
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException("barrier wait failed", e);
        }
    }

    private Category leafCategory() {
        return categoryRepository.save(new Category(
                UUID.randomUUID().toString(), "ConcurrentPublishIT-" + UUID.randomUUID(), null,
                List.of("Hardware"), List.of(), List.of(), "test-admin", java.time.Instant.now(), 0L));
    }

    private ResponseEntity<Map> publish(String categoryId, String uniqueTitleWord, String price, String shippingCost) {
        String sellerSub = UUID.randomUUID().toString();
        String token = TestJwtSupport.mint(sellerSub);

        Map<String, Object> body = new HashMap<>();
        body.put("title", uniqueTitleWord + " RTX 3080");
        body.put("description", "Barely used, no box");
        body.put("price", price);
        body.put("categoryId", categoryId);
        body.put("photos", List.of());
        body.put("paymentMethods", List.of("BANK_TRANSFER"));
        body.put("condition", "VERY_GOOD");
        body.put("shippingLocation", "Milano");
        body.put("shippingCost", shippingCost);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange("/listings/hardware", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
