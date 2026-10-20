package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

/**
 * Proves ADMIN-01 against real MongoDB, not mocks: a non-admin token gets
 * 403 with an RFC 9457 body; the fixed-roots and sibling-homonymy rules hold
 * under a real concurrent race, not just a sequential check-then-act; and
 * the leaf guard closes both the sequential and the concurrent
 * create-vs-publish race by document contention, exactly as
 * {@code CategoryService}/{@code ListingPublishService}'s conditional
 * updates are supposed to.
 *
 * <p>Only {@link MongoTestBase} — no Rabbit or Meilisearch container needed:
 * nothing here drains the outbox or reads back from search.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class CategoryAdminIT extends MongoTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void nonAdminTokenGetsForbiddenWithProblemJsonBody() {
        String sellerToken = sellerToken();

        ResponseEntity<Map> response = createCategory(sellerToken, "Peripherals-" + UUID.randomUUID(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(response.getBody()).isNotNull();
        assertThat((Object) response.getBody().get("status")).isEqualTo(403);
        assertThat(response.getBody().get("type").toString()).startsWith("urn:rebyte:problem:");
    }

    @Test
    void rootWithInvalidNameIsRejected() {
        ResponseEntity<Map> response = createCategory(adminToken(), "Peripherals-" + UUID.randomUUID(), null);

        // HttpStatus carries two distinct enum constants for code 422
        // (UNPROCESSABLE_ENTITY, deprecated, and UNPROCESSABLE_CONTENT, its
        // RFC 9110 replacement) — HttpStatus.valueOf(422), which
        // TestRestTemplate uses to reconstruct the status from the wire
        // response, resolves to UNPROCESSABLE_CONTENT, so that is what an
        // equality assertion must compare against even though the
        // production code throws with UNPROCESSABLE_ENTITY (same wire code).
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void rootThatAlreadyExistsIsRejected() {
        // CategoryRootBootstrap has already seeded "Hardware" by the time
        // any *IT context is up — no setup needed to make this collide.
        ResponseEntity<Map> response = createCategory(adminToken(), "Hardware", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void adminCanCreateAChildUnderAnExistingParent() {
        Category hardwareRoot = requireHardwareRoot();

        ResponseEntity<Map> response = createCategory(adminToken(), "AdminIT-Child-" + UUID.randomUUID(), hardwareRoot.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat((Boolean) response.getBody().get("leaf")).isTrue();
    }

    @Test
    void twoConcurrentCreationsOfTheSameSiblingNameLeaveExactlyOneCategoryAndOneConflict() {
        Category hardwareRoot = requireHardwareRoot();
        String parentId = createCategoryOrThrow(adminToken(), "RaceParent-" + UUID.randomUUID(), hardwareRoot.getId());
        String childName = "Duplicate-" + UUID.randomUUID();
        String admin = adminToken();

        CompletableFuture<ResponseEntity<Map>> first =
                CompletableFuture.supplyAsync(() -> createCategory(admin, childName, parentId));
        CompletableFuture<ResponseEntity<Map>> second =
                CompletableFuture.supplyAsync(() -> createCategory(admin, childName, parentId));
        CompletableFuture.allOf(first, second).join();

        List<HttpStatus> statuses = List.of(
                (HttpStatus) first.join().getStatusCode(), (HttpStatus) second.join().getStatusCode());
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);

        long matchingChildren = categoryRepository.findAll().stream()
                .filter(c -> childName.equals(c.getName()) && parentId.equals(c.getParentId()))
                .count();
        assertThat(matchingChildren).isEqualTo(1);
    }

    @Test
    void createChildUnderParentThatAlreadyHoldsAListingIsRejected() {
        Category hardwareRoot = requireHardwareRoot();
        String parentId = createCategoryOrThrow(adminToken(), "SeqRace-" + UUID.randomUUID(), hardwareRoot.getId());

        ResponseEntity<Map> publishResponse = publishHardware(sellerToken(), parentId, "SeqRaceItem" + UUID.randomUUID());
        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> createChildResponse = createCategory(adminToken(), "TooLate-" + UUID.randomUUID(), parentId);

        assertThat(createChildResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void concurrentChildCreationAndPublishIntoTheSameParentNeverBothCommit() {
        Category hardwareRoot = requireHardwareRoot();
        String parentId = createCategoryOrThrow(adminToken(), "RaceParent2-" + UUID.randomUUID(), hardwareRoot.getId());

        CompletableFuture<ResponseEntity<Map>> createChild = CompletableFuture.supplyAsync(
                () -> createCategory(adminToken(), "RaceChild-" + UUID.randomUUID(), parentId));
        CompletableFuture<ResponseEntity<Map>> publish = CompletableFuture.supplyAsync(
                () -> publishHardware(sellerToken(), parentId, "RaceItem" + UUID.randomUUID()));
        CompletableFuture.allOf(createChild, publish).join();

        Category afterRace = categoryRepository.findById(parentId).orElseThrow();
        boolean childCommitted = !afterRace.getSubcategoryIds().isEmpty();
        boolean listingCommitted = afterRace.getListingCount() > 0;

        assertThat(childCommitted ^ listingCommitted)
                .as("exactly one of the two racing writes may commit, never both, never neither")
                .isTrue();
    }

    private Category requireHardwareRoot() {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getParentId() == null && "Hardware".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CategoryRootBootstrap did not seed the Hardware root"));
    }

    private String createCategoryOrThrow(String token, String name, String parentId) {
        ResponseEntity<Map> response = createCategory(token, name, parentId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private String adminToken() {
        return TestJwtSupport.mint(UUID.randomUUID().toString(), List.of("ADMIN"));
    }

    private String sellerToken() {
        return TestJwtSupport.mint(UUID.randomUUID().toString());
    }

    private ResponseEntity<Map> createCategory(String token, String name, String parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("parentId", parentId);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange("/categories", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> publishHardware(String token, String categoryId, String uniqueTitleWord) {
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
}
