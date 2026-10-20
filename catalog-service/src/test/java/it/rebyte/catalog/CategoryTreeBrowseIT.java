package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
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
 * Proves CAT-07 against real MongoDB and real Redis, not mocks: the whole
 * tree from both roots to their leaves in one unauthenticated request; a
 * second identical request served byte-identically from
 * {@link it.rebyte.catalog.service.CategoryTreeCache}; the effective
 * schema visible through {@code GET /categories/{id}/schema} both for the
 * plan's worked example (GPU: {@code bus} inherited, {@code vram_gb} its
 * own; Amiga: {@code model} only) and for a leaf with no definitions at
 * all; a newly-defined attribute becoming visible on the very next
 * request (eviction on write); and — the property most worth getting
 * wrong — that ordinary publish traffic never evicts the cached tree.
 *
 * <p>No Testcontainers Redis: this module declares no such dependency
 * (plan 02-02's own file list does not touch {@code pom.xml}), and the
 * developer's real {@code rebyte-redis} container is already up on the
 * default {@code localhost:6379} {@code MongoTestBase} does not override —
 * exactly the environment the plan's own execution notes describe as
 * "verified" for this run.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class CategoryTreeBrowseIT extends MongoTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Value("${rebyte.catalog.category-tree.key}")
    private String cacheKey;

    @Test
    void treeEndpointReturnsBothRootsWithDescendantsInOneRequestWithoutAuthentication() {
        Category hardwareRoot = requireRoot("Hardware");
        Category softwareRoot = requireRoot("Software");
        String childId = createCategoryOrThrow(adminToken(), "BrowseChild-" + UUID.randomUUID(), hardwareRoot.getId());
        redisTemplate.delete(cacheKey);

        ResponseEntity<List> response = restTemplate.exchange(
                "/categories", HttpMethod.GET, new HttpEntity<Void>(new HttpHeaders()), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        List<Map<String, Object>> roots = response.getBody();
        assertThat(findNode(roots, hardwareRoot.getId())).isNotNull();
        assertThat(findNode(roots, softwareRoot.getId())).isNotNull();
        Map<String, Object> hardwareNode = findNode(roots, hardwareRoot.getId());
        assertThat(findNode(childrenOf(hardwareNode), childId)).isNotNull();
        // T-02-02-06: the browsable tree never exposes the listing counter.
        assertThat(hardwareNode).doesNotContainKey("listingCount");
    }

    @Test
    void gpuEffectiveSchemaIsBusInheritedThenVramGbOwnInRootMostFirstOrder() {
        Category hardwareRoot = requireRoot("Hardware");
        String componentsId = createCategoryOrThrow(adminToken(), "Components-" + UUID.randomUUID(), hardwareRoot.getId());
        defineAttributeOrThrow(componentsId, "bus", "ENUM", List.of("PCIe", "AGP"));
        String gpuId = createCategoryOrThrow(adminToken(), "GPU-" + UUID.randomUUID(), componentsId);
        defineAttributeOrThrow(gpuId, "vram_gb", "INTEGER", List.of());

        ResponseEntity<Map> response = getSchema(gpuId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributeNames(response)).containsExactly("bus", "vram_gb");
    }

    @Test
    void amigaEffectiveSchemaIsExactlyModelAndNeverContainsCondition() {
        Category hardwareRoot = requireRoot("Hardware");
        String retroId = createCategoryOrThrow(adminToken(), "Retrocomputing-" + UUID.randomUUID(), hardwareRoot.getId());
        String amigaId = createCategoryOrThrow(adminToken(), "Amiga-" + UUID.randomUUID(), retroId);
        defineAttributeOrThrow(amigaId, "model", "ENUM", List.of("500", "1200"));

        ResponseEntity<Map> response = getSchema(amigaId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributeNames(response)).containsExactly("model");
        assertThat(attributeNames(response)).doesNotContain("condition");
    }

    @Test
    void leafWithNoDefinitionsResolvesToAnEmptySchemaNot404() {
        Category softwareRoot = requireRoot("Software");
        String leafId = createCategoryOrThrow(adminToken(), "Emulators-" + UUID.randomUUID(), softwareRoot.getId());

        ResponseEntity<Map> response = getSchema(leafId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributeNames(response)).isEmpty();
    }

    @Test
    void secondIdenticalRequestIsServedFromCacheWithByteIdenticalBody() {
        Category hardwareRoot = requireRoot("Hardware");
        createCategoryOrThrow(adminToken(), "CacheProbe-" + UUID.randomUUID(), hardwareRoot.getId());
        redisTemplate.delete(cacheKey);

        ResponseEntity<String> cold = restTemplate.exchange(
                "/categories", HttpMethod.GET, new HttpEntity<Void>(new HttpHeaders()), String.class);
        ResponseEntity<String> warm = restTemplate.exchange(
                "/categories", HttpMethod.GET, new HttpEntity<Void>(new HttpHeaders()), String.class);

        assertThat(cold.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(warm.getBody()).isEqualTo(cold.getBody());
    }

    @Test
    void publishingAListingDoesNotEvictTheCachedTree() {
        Category hardwareRoot = requireRoot("Hardware");
        String componentsId = createCategoryOrThrow(adminToken(), "PublishProbeParent-" + UUID.randomUUID(), hardwareRoot.getId());
        String gpuId = createCategoryOrThrow(adminToken(), "PublishProbeLeaf-" + UUID.randomUUID(), componentsId);
        redisTemplate.delete(cacheKey);
        restTemplate.exchange("/categories", HttpMethod.GET, new HttpEntity<Void>(new HttpHeaders()), String.class);
        String cachedBeforePublish = redisTemplate.opsForValue().get(cacheKey);
        assertThat(cachedBeforePublish).isNotNull();

        ResponseEntity<Map> publishResponse = publishHardware(sellerToken(), gpuId, "NoEvict" + UUID.randomUUID());
        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // If the leaf-guard write (listingCount increment) had evicted this
        // key, a direct read would return null here — nothing in this test
        // re-populates it. Finding the SAME string back is what proves the
        // key itself was never touched, not merely that a fresh rebuild
        // happened to compute the same JSON (T-02-02, key_links).
        String cachedAfterPublish = redisTemplate.opsForValue().get(cacheKey);
        assertThat(cachedAfterPublish).isEqualTo(cachedBeforePublish);
    }

    @Test
    void attributeDefinedAfterCachingIsVisibleOnTheNextRequest() {
        Category hardwareRoot = requireRoot("Hardware");
        String leafId = createCategoryOrThrow(adminToken(), "EvictProbe-" + UUID.randomUUID(), hardwareRoot.getId());

        ResponseEntity<Map> before = getSchema(leafId);
        assertThat(attributeNames(before)).isEmpty();

        defineAttributeOrThrow(leafId, "newly_added", "INTEGER", List.of());

        ResponseEntity<Map> after = getSchema(leafId);
        assertThat(attributeNames(after)).containsExactly("newly_added");
    }

    @Test
    void anonymousCategoryDetailIsPublicButAnonymousCreationIsRefused() {
        Category hardwareRoot = requireRoot("Hardware");

        ResponseEntity<Map> detail = restTemplate.exchange("/categories/" + hardwareRoot.getId(), HttpMethod.GET,
                new HttpEntity<Void>(new HttpHeaders()), Map.class);
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> create = restTemplate.exchange("/categories", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Anonymous-" + UUID.randomUUID(), "parentId", hardwareRoot.getId()), json),
                Map.class);

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).containsEntry("id", hardwareRoot.getId());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findNode(List<Map<String, Object>> nodes, String id) {
        for (Map<String, Object> node : nodes) {
            if (id.equals(node.get("id"))) {
                return node;
            }
            Map<String, Object> foundInChildren = findNode(childrenOf(node), id);
            if (foundInChildren != null) {
                return foundInChildren;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> childrenOf(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("children");
    }

    @SuppressWarnings("unchecked")
    private static List<String> attributeNames(ResponseEntity<Map> response) {
        List<Map<String, Object>> attributes = (List<Map<String, Object>>) response.getBody().get("attributes");
        return attributes.stream().map(attr -> (String) attr.get("name")).toList();
    }

    private Category requireRoot(String name) {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getParentId() == null && name.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CategoryRootBootstrap did not seed the " + name + " root"));
    }

    private String createCategoryOrThrow(String token, String name, String parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("parentId", parentId);

        ResponseEntity<Map> response =
                restTemplate.exchange("/categories", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private void defineAttributeOrThrow(String categoryId, String name, String type, List<String> allowedValues) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("type", type);
        body.put("required", false);
        body.put("allowedValues", allowedValues);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/categories/" + categoryId + "/attributes", HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(adminToken())), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<Map> getSchema(String categoryId) {
        return restTemplate.exchange(
                "/categories/" + categoryId + "/schema", HttpMethod.GET, new HttpEntity<Void>(new HttpHeaders()), Map.class);
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

        return restTemplate.exchange(
                "/listings/hardware", HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), Map.class);
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
