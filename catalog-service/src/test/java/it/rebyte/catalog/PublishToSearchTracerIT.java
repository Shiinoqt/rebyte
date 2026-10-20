package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.dto.SearchResultPage;
import it.rebyte.catalog.outbox.OutboxEvent;
import it.rebyte.catalog.outbox.OutboxRelay;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

/**
 * The one end-to-end proof this plan exists to produce: a seller publishes
 * one hardware listing over real HTTP, MongoDB commits the listing document
 * and its outbox document in one session, the relay drains it to RabbitMQ,
 * the indexer projects it into Meilisearch, and a free-text search returns
 * it — against real containers, no test code touching Meilisearch directly.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class PublishToSearchTracerIT extends CatalogInfraTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Test
    void publishedHardwareListingBecomesFindableEndToEnd() {
        Category category = categoryRepository.save(new Category(
                UUID.randomUUID().toString(), "GPU", null, List.of("Hardware"),
                List.of(), List.of(), "test-admin", Instant.now(), 0L));
        String uniqueTitleWord = "Tracerprobe" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<Map> publishResponse = publishHardware(category.getId(), uniqueTitleWord);

        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String listingId = (String) publishResponse.getBody().get("id");
        assertThat(listingId).isNotBlank();

        assertThat(listingRepository.findById(listingId)).isPresent();
        long outboxRowsForThisListing = outboxRepository.findAll().stream()
                .filter(event -> event.getPayload().contains(listingId))
                .count();
        assertThat(outboxRowsForThisListing).isEqualTo(1);

        outboxRelay.drainUntilEmptyOrCapped();

        OutboxEvent outboxEvent = outboxRepository.findAll().stream()
                .filter(event -> event.getPayload().contains(listingId))
                .findFirst()
                .orElseThrow();
        assertThat(outboxEvent.getPublishedAt()).isNotNull();

        SearchResultPage page = pollUntilQueryReturnsAHit(uniqueTitleWord);
        assertThat(page.hits()).hasSize(1);
        assertThat(page.hits().get(0).id()).isEqualTo(listingId);
        assertThat(page.hits().get(0).title()).contains(uniqueTitleWord);
    }

    private ResponseEntity<Map> publishHardware(String categoryId, String uniqueTitleWord) {
        String sellerSub = UUID.randomUUID().toString();
        String token = TestJwtSupport.mint(sellerSub);

        Map<String, Object> body = new HashMap<>();
        body.put("title", uniqueTitleWord + " RTX 3080");
        body.put("description", "Barely used, no box");
        body.put("price", "450.00");
        body.put("categoryId", categoryId);
        body.put("photos", List.of());
        body.put("paymentMethods", List.of("BANK_TRANSFER"));
        body.put("condition", "VERY_GOOD");
        body.put("shippingLocation", "Milano");
        body.put("shippingCost", "10.00");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange("/listings/hardware", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private SearchResultPage pollUntilQueryReturnsAHit(String query) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<SearchResultPage> response =
                    restTemplate.getForEntity("/search?q=" + query, SearchResultPage.class);
            if (response.getBody() != null && !response.getBody().hits().isEmpty()) {
                return response.getBody();
            }
            sleep();
        }
        throw new AssertionError("query \"" + query + "\" did not return a hit within the polling timeout");
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
