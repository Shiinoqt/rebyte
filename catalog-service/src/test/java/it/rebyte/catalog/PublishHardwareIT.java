package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.dto.SearchResultPage;
import it.rebyte.catalog.error.DuplicateAttributeNameException;
import it.rebyte.catalog.dto.DefineAttributeRequest;
import it.rebyte.catalog.outbox.OutboxRelay;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.service.AttributeDefinitionService;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

/**
 * ATTR-03 and CAT-01 end to end against real containers: a hardware listing
 * cannot be published into a leaf without satisfying that leaf's inherited
 * schema, and a published one is findable in search. {@code
 * AttributeValidationTest} carries the same rules unit-tested against
 * {@code AttributeValueValidator} directly, no container required.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class PublishHardwareIT extends CatalogInfraTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private AttributeDefinitionService attributeDefinitionService;

    @Test
    void completePublishWithEveryRequiredAttributeReturns201AndIsFindableInSearch() {
        AttributeDefinition bus = attributeDefinition("bus", AttributeType.ENUM, true, List.of("PCIe", "AGP"));
        Category category = leafCategory(List.of(bus));
        String uniqueTitleWord = "Attrprobe" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<Map> response = publish(category.getId(), uniqueTitleWord,
                List.of(attributeInput(bus.getId(), "PCIe")), List.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String listingId = (String) response.getBody().get("id");
        assertThat(listingRepository.findById(listingId)).isPresent();

        outboxRelay.drainUntilEmptyOrCapped();
        SearchResultPage page = pollUntilQueryReturnsAHit(uniqueTitleWord);
        assertThat(page.hits()).hasSize(1);
    }

    @Test
    void publishMissingARequiredAttributeReturns422NamingItAndWritesNothing() {
        AttributeDefinition bus = attributeDefinition("bus", AttributeType.ENUM, true, List.of("PCIe", "AGP"));
        Category category = leafCategory(List.of(bus));
        long countBefore = listingRepository.count();

        ResponseEntity<Map> response = publish(category.getId(), "Missing attribute probe", List.of(), List.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(String.valueOf(response.getBody().get("detail"))).contains("bus");
        assertThat(listingRepository.count()).isEqualTo(countBefore);
    }

    @Test
    void textValueForAnIntegerAttributeReturns422() {
        AttributeDefinition vramGb = attributeDefinition("vram_gb", AttributeType.INTEGER, true, List.of());
        Category category = leafCategory(List.of(vramGb));

        ResponseEntity<Map> response = publish(category.getId(), "Type mismatch probe",
                List.of(attributeInput(vramGb.getId(), "sixteen")), List.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void retiredEnumValueReturns422() {
        AttributeDefinition bus = attributeDefinition("bus", AttributeType.ENUM, true, List.of("PCIe", "AGP"));
        Category category = leafCategory(List.of(bus));

        ResponseEntity<Map> response = publish(category.getId(), "Retired enum probe",
                List.of(attributeInput(bus.getId(), "ISA")), List.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void attributeIdAbsentFromTheEffectiveSchemaReturns422() {
        Category category = leafCategory(List.of());

        ResponseEntity<Map> response = publish(category.getId(), "Unknown attribute probe",
                List.of(attributeInput(UUID.randomUUID().toString(), "anything")), List.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aListingAlreadyCarryingARetiredEnumValueIsUntouchedInMongoAfterRetirement() {
        AttributeDefinition bus = attributeDefinition("bus", AttributeType.ENUM, true, List.of("PCIe", "AGP"));
        Category category = leafCategory(List.of(bus));

        ResponseEntity<Map> publishResponse = publish(category.getId(), "D-07 asymmetry probe",
                List.of(attributeInput(bus.getId(), "PCIe")), List.of());
        String listingId = (String) publishResponse.getBody().get("id");

        attributeDefinitionService.retireAllowedValue(category.getId(), bus.getId(), "PCIe");

        Listing stored = listingRepository.findById(listingId).orElseThrow();
        AttributeValue storedValue = stored.getAttributes().get(bus.getId());
        assertThat(storedValue).isNotNull();
        assertThat(storedValue.getStringValue()).isEqualTo("PCIe");
    }

    @Test
    void hardwareListingDeclaresConditionShippingLocationAndShippingCostAsFixedFields() {
        assertThat(it.rebyte.catalog.domain.HardwareListing.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .contains("condition", "shippingLocation", "shippingCost");
    }

    @Test
    void definingAnAttributeNamedConditionUnderTheHardwareRootReturns409() {
        Category category = leafCategory(List.of());
        DefineAttributeRequest request = new DefineAttributeRequest("condition", AttributeType.ENUM, false,
                List.of("anything"), null);

        assertThatThrownBy(() -> attributeDefinitionService.define("test-admin", category.getId(), request))
                .isInstanceOf(DuplicateAttributeNameException.class);
    }

    @Test
    void definingAnAttributeNamedShippingLocationOrShippingCostUnderTheHardwareRootReturns409() {
        Category category = leafCategory(List.of());

        assertThatThrownBy(() -> attributeDefinitionService.define("test-admin", category.getId(),
                new DefineAttributeRequest("shippingLocation", AttributeType.INTEGER, false, List.of(), null)))
                .isInstanceOf(DuplicateAttributeNameException.class);
        assertThatThrownBy(() -> attributeDefinitionService.define("test-admin", category.getId(),
                new DefineAttributeRequest("shippingCost", AttributeType.INTEGER, false, List.of(), null)))
                .isInstanceOf(DuplicateAttributeNameException.class);
    }

    @Test
    void onePhotoKeyReturns422AndNoListingIsWritten() {
        Category category = leafCategory(List.of());
        long countBefore = listingRepository.count();

        ResponseEntity<Map> response = publish(category.getId(), "One photo probe", List.of(),
                List.of("listings/another-listing/a"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(response.getBody()).containsEntry("title", "photo-key-not-on-listing")
                .containsEntry("detail", "Photo list contains 1 rejected entries");
        assertThat(listingRepository.count()).isEqualTo(countBefore);
        assertThat(categoryRepository.findById(category.getId()).orElseThrow().getListingCount()).isZero();
    }

    @Test
    void sixPhotoKeysReturns422AndNoListingIsWritten() {
        Category category = leafCategory(List.of());
        long countBefore = listingRepository.count();
        List<String> sixPhotos = List.of("p1", "p2", "p3", "p4", "p5", "p6");

        ResponseEntity<Map> response = publish(category.getId(), "Six photos probe", List.of(), sixPhotos);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).containsEntry("title", "photo-limit-exceeded");
        assertThat(listingRepository.count()).isEqualTo(countBefore);
    }

    private AttributeDefinition attributeDefinition(String name, AttributeType type, boolean required, List<String> allowedValues) {
        return new AttributeDefinition(UUID.randomUUID().toString(), name, type, required, allowedValues, null, Instant.now());
    }

    private Category leafCategory(List<AttributeDefinition> definitions) {
        return categoryRepository.save(new Category(
                UUID.randomUUID().toString(), "GPU-" + UUID.randomUUID(), null, List.of("Hardware"),
                List.of(), definitions, "test-admin", Instant.now(), 0L));
    }

    private Map<String, Object> attributeInput(String attributeId, Object value) {
        Map<String, Object> input = new HashMap<>();
        input.put("attributeId", attributeId);
        input.put("value", value);
        return input;
    }

    private ResponseEntity<Map> publish(
            String categoryId, String uniqueTitleWord, List<Map<String, Object>> attributes, List<String> photos) {
        String sellerSub = UUID.randomUUID().toString();
        String token = TestJwtSupport.mint(sellerSub);

        Map<String, Object> body = new HashMap<>();
        body.put("title", uniqueTitleWord + " RTX 3080");
        body.put("description", "Barely used, no box");
        body.put("price", "450.00");
        body.put("categoryId", categoryId);
        body.put("photos", photos);
        body.put("paymentMethods", List.of("BANK_TRANSFER"));
        body.put("condition", "VERY_GOOD");
        body.put("shippingLocation", "Milano");
        body.put("shippingCost", "10.00");
        body.put("attributes", attributes);

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
