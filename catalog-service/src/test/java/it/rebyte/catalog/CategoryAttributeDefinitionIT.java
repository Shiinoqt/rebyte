package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.mongodb.client.model.Filters;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

/**
 * Proves ATTR-02 against real MongoDB and real HTTP, not mocks: a non-admin
 * token gets 403; D-05's uniqueness-to-root check returns 409 naming the
 * ancestor that already holds the colliding name; the same name on two
 * different branches of the tree succeeds on both; and D-07 retirement
 * removes a value from the definition response while leaving an existing
 * listing's stored value untouched.
 *
 * <p>{@code Listing} does not carry attribute values yet (that ships in a
 * later plan) — the retirement test writes directly into the {@code
 * listing} collection as raw BSON, the same "insert a fixture MongoDB
 * document, bypass the domain object" technique {@code SearchTextMatchingIT}
 * uses for its SOLD-listing fixture, so this plan does not need to add
 * premature attribute-value support to {@link it.rebyte.catalog.domain.Listing}
 * just to prove D-07 does not touch existing data.
 *
 * <p>Only {@link MongoTestBase} — no Rabbit or Meilisearch container needed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class CategoryAttributeDefinitionIT extends MongoTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void nonAdminTokenGetsForbidden() {
        String parentId = createLeafUnderHardware("NonAdminAttrParent-" + UUID.randomUUID());

        ResponseEntity<Map> response = defineAttribute(sellerToken(), parentId, "color", "ENUM", List.of("Red"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    void definingAnAttributeNameCollidingWithAnAncestorReturns409NamingTheAncestor() {
        String ancestorId = createLeafUnderHardware("AttrAncestor-" + UUID.randomUUID());
        ResponseEntity<Map> defineOnAncestor = defineAttribute(adminToken(), ancestorId, "vram_gb", "INTEGER", List.of());
        assertThat(defineOnAncestor.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String childId = createCategoryOrThrow(adminToken(), "AttrChild-" + UUID.randomUUID(), ancestorId);

        // Case- and accent-folded collision (D-05, edge: encoding) — not a
        // literal repeat of the same string.
        ResponseEntity<Map> defineOnChild = defineAttribute(adminToken(), childId, "Vram_GB", "INTEGER", List.of());

        assertThat(defineOnChild.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(defineOnChild.getBody()).isNotNull();
        assertThat(defineOnChild.getBody().get("detail").toString()).contains(ancestorId);
    }

    @Test
    void sameAttributeNameOnTwoDifferentBranchesSucceedsOnBoth() {
        Category hardwareRoot = requireHardwareRoot();
        String branchA = createCategoryOrThrow(adminToken(), "BranchA-" + UUID.randomUUID(), hardwareRoot.getId());
        String branchB = createCategoryOrThrow(adminToken(), "BranchB-" + UUID.randomUUID(), hardwareRoot.getId());
        String attributeName = "weight_kg-" + UUID.randomUUID();

        ResponseEntity<Map> onA = defineAttribute(adminToken(), branchA, attributeName, "DECIMAL", List.of());
        ResponseEntity<Map> onB = defineAttribute(adminToken(), branchB, attributeName, "DECIMAL", List.of());

        assertThat(onA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(onB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void retiringAnAllowedValueRemovesItFromTheResponseWhileAnExistingListingKeepsIt() {
        String categoryId = createLeafUnderHardware("RetireCat-" + UUID.randomUUID());
        ResponseEntity<Map> defineResponse = defineAttribute(
                adminToken(), categoryId, "color-" + UUID.randomUUID(), "ENUM", List.of("Red", "Blue"));
        assertThat(defineResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String attributeId = (String) defineResponse.getBody().get("id");

        // A listing carrying "Red" for this attribute, written directly as
        // raw BSON — Listing has no attribute-value field yet (see class
        // Javadoc).
        String listingId = UUID.randomUUID().toString();
        mongoTemplate.getCollection("listing").insertOne(new Document(Map.of(
                "id", listingId,
                "attributeValues", new Document(attributeId, "Red"))));

        ResponseEntity<Map> retireResponse = restTemplate.exchange(
                "/categories/" + categoryId + "/attributes/" + attributeId + "/allowed-values/Red",
                HttpMethod.DELETE, new HttpEntity<Void>(authHeaders(adminToken())), Map.class);

        assertThat(retireResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retireResponse.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        List<String> remainingAllowedValues = (List<String>) retireResponse.getBody().get("allowedValues");
        assertThat(remainingAllowedValues).containsExactly("Blue");

        Document storedListing = mongoTemplate.getCollection("listing").find(Filters.eq("id", listingId)).first();
        assertThat(storedListing).isNotNull();
        Document storedAttributeValues = storedListing.get("attributeValues", Document.class);
        assertThat(storedAttributeValues.getString(attributeId)).isEqualTo("Red");
    }

    private Category requireHardwareRoot() {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getParentId() == null && "Hardware".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CategoryRootBootstrap did not seed the Hardware root"));
    }

    private String createLeafUnderHardware(String name) {
        return createCategoryOrThrow(adminToken(), name, requireHardwareRoot().getId());
    }

    private String createCategoryOrThrow(String token, String name, String parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("parentId", parentId);

        HttpHeaders headers = authHeaders(token);
        ResponseEntity<Map> response =
                restTemplate.exchange("/categories", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private ResponseEntity<Map> defineAttribute(
            String token, String categoryId, String name, String type, List<String> allowedValues) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("type", type);
        body.put("required", false);
        body.put("allowedValues", allowedValues);

        HttpHeaders headers = authHeaders(token);
        return restTemplate.exchange(
                "/categories/" + categoryId + "/attributes", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String adminToken() {
        return TestJwtSupport.mint(UUID.randomUUID().toString(), List.of("ADMIN"));
    }

    private String sellerToken() {
        return TestJwtSupport.mint(UUID.randomUUID().toString());
    }
}
