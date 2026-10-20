package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.rebyte.catalog.client.UserServiceClient;
import it.rebyte.catalog.client.UserSummary;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class EditListingIT extends MongoTestBase {

    @Autowired TestRestTemplate http;
    @Autowired CategoryRepository categories;
    @Autowired ListingRepository listings;
    @MockitoBean UserServiceClient users;

    @BeforeEach
    void clearCollections() {
        listings.deleteAll();
        categories.deleteAll();
    }

    @Test
    void movingListingUpdatesBothCategoryCountersAtomically() {
        Category source = category("Source", "Hardware", 1);
        Category destination = category("Destination", "Hardware", 0);
        Listing listing = listing(source.getId(), "seller", ListingStatus.ACTIVE);

        ResponseEntity<Map> response = edit("seller", listing.getId(), destination.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listings.findById(listing.getId()).orElseThrow().getCategoryId()).isEqualTo(destination.getId());
        assertThat(categories.findById(source.getId()).orElseThrow().getListingCount()).isZero();
        assertThat(categories.findById(destination.getId()).orElseThrow().getListingCount()).isEqualTo(1);
    }

    @Test
    void nonOwnerCrossRootAndRemovedListingAreRefusedWithoutMoving() {
        Category hardware = category("Hardware leaf", "Hardware", 1);
        Category software = category("Software leaf", "Software", 0);
        Listing active = listing(hardware.getId(), "seller", ListingStatus.ACTIVE);

        assertThat(edit("intruder", active.getId(), hardware.getId()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(edit("seller", active.getId(), software.getId()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(listings.findById(active.getId()).orElseThrow().getCategoryId()).isEqualTo(hardware.getId());

        Listing removed = listing(hardware.getId(), "seller", ListingStatus.REMOVED);
        assertThat(edit("seller", removed.getId(), hardware.getId()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void moveCarriesInheritedValuesAppliesOverridesDropsInapplicableValuesAndReportsThem() {
        String inheritedId = UUID.randomUUID().toString();
        String sourceOnlyId = UUID.randomUUID().toString();
        String destinationRequiredId = UUID.randomUUID().toString();
        Category root = categories.save(new Category(UUID.randomUUID().toString(), "Hardware", null, List.of(), List.of(),
                List.of(definition(inheritedId, "ram_gb", true)), "admin", Instant.now(), 0));
        Category source = categories.save(new Category(UUID.randomUUID().toString(), "Source", root.getId(), List.of("Hardware"),
                List.of(), List.of(definition(sourceOnlyId, "socket", false)), "admin", Instant.now(), 1));
        Category destination = categories.save(new Category(UUID.randomUUID().toString(), "Destination", root.getId(), List.of("Hardware"),
                List.of(), List.of(definition(destinationRequiredId, "warranty_months", true)), "admin", Instant.now(), 0));
        Listing listing = listings.save(new Listing(UUID.randomUUID().toString(), "Original", "Description", new BigDecimal("100.00"),
                Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL), "seller", source.getId(),
                new HardwareListing(Condition.GOOD, "Rome", new BigDecimal("5.00")), null,
                Map.of(inheritedId, AttributeValue.ofInteger(8), sourceOnlyId, AttributeValue.ofInteger(1))));

        ResponseEntity<Map> response = edit("seller", listing.getId(), destination.getId(), List.of(
                Map.of("attributeId", inheritedId, "value", 16),
                Map.of("attributeId", destinationRequiredId, "value", 24)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Listing moved = listings.findById(listing.getId()).orElseThrow();
        assertThat(moved.getAttributes().get(inheritedId).getIntegerValue()).isEqualTo(16);
        assertThat(moved.getAttributes().get(destinationRequiredId).getIntegerValue()).isEqualTo(24);
        assertThat(moved.getAttributes()).doesNotContainKey(sourceOnlyId);
        assertThat(response.getBody().get("droppedAttributeIds")).isEqualTo(List.of(sourceOnlyId));
        assertThat(categories.findById(source.getId()).orElseThrow().getListingCount()).isZero();
        assertThat(categories.findById(destination.getId()).orElseThrow().getListingCount()).isEqualTo(1);
    }

    @Test
    void moveMissingDestinationRequiredValueRollsBackTheListingAndBothCounters() {
        String requiredId = UUID.randomUUID().toString();
        Category source = category("Source", "Hardware", 1);
        Category destination = categories.save(new Category(UUID.randomUUID().toString(), "Destination", null, List.of("Hardware"),
                List.of(), List.of(definition(requiredId, "required", true)), "admin", Instant.now(), 0));
        Listing listing = listing(source.getId(), "seller", ListingStatus.ACTIVE);

        assertThat(edit("seller", listing.getId(), destination.getId(), List.of()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(listings.findById(listing.getId()).orElseThrow().getCategoryId()).isEqualTo(source.getId());
        assertThat(categories.findById(source.getId()).orElseThrow().getListingCount()).isEqualTo(1);
        assertThat(categories.findById(destination.getId()).orElseThrow().getListingCount()).isZero();
    }

    @Test
    void storedPhotoKeysCanBeReorderedAndDetailKeepsTheNewOrder() {
        Category category = category("Photos", "Hardware", 1);
        Listing listing = listingWithPhotos(category.getId());
        List<String> reordered = List.of(listing.getPhotos().get(1), listing.getPhotos().get(0));
        when(users.getSummary("seller")).thenReturn(new UserSummary("seller", "seller", null, false, null, 0));

        ResponseEntity<Map> response = edit("seller", listing.getId(), category.getId(), List.of(), reordered);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listings.findById(listing.getId()).orElseThrow().getPhotos()).containsExactlyElementsOf(reordered);
        ResponseEntity<Map> detail = http.getForEntity("/listings/" + listing.getId(), Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().get("photoKeys")).isEqualTo(reordered);
        assertThat(detail.getBody().get("photos"))
                .isEqualTo(reordered.stream().map(key -> "https://cdn.example.test/" + key).toList());
    }

    @Test
    void storedPhotoKeysCanBeRemoved() {
        Category category = category("Photos", "Hardware", 1);
        Listing listing = listingWithPhotos(category.getId());
        List<String> remaining = List.of(listing.getPhotos().get(1));

        ResponseEntity<Map> response = edit("seller", listing.getId(), category.getId(), List.of(), remaining);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listings.findById(listing.getId()).orElseThrow().getPhotos()).containsExactlyElementsOf(remaining);
    }

    @Test
    void anotherListingsPhotoKeyIsRefusedWithoutWriting() {
        Category category = category("Photos", "Hardware", 2);
        Listing listing = listingWithPhotos(category.getId());
        Listing other = listingWithPhotos(category.getId());

        assertRejectedPhotoEdit(listing, List.of(listing.getPhotos().get(0), other.getPhotos().get(0)), 1);
    }

    @Test
    void storedPhotoKeySubmittedAsACdnUrlIsRefusedWithoutWriting() {
        Category category = category("Photos", "Hardware", 1);
        Listing listing = listingWithPhotos(category.getId());

        assertRejectedPhotoEdit(listing, List.of("https://cdn.example.test/" + listing.getPhotos().get(0)), 1);
    }

    @Test
    void duplicateStoredPhotoKeyIsRefusedWithoutWriting() {
        Category category = category("Photos", "Hardware", 1);
        Listing listing = listingWithPhotos(category.getId());

        assertRejectedPhotoEdit(listing, List.of(listing.getPhotos().get(0), listing.getPhotos().get(0)), 1);
    }

    private void assertRejectedPhotoEdit(Listing listing, List<String> submitted, int rejectedCount) {
        Category destination = category("Destination", "Hardware", 0);
        long sourceCount = categories.findById(listing.getCategoryId()).orElseThrow().getListingCount();

        ResponseEntity<Map> response = edit("seller", listing.getId(), destination.getId(), List.of(), submitted);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(response.getBody()).containsEntry("title", "photo-key-not-on-listing")
                .containsEntry("detail", "Photo list contains " + rejectedCount + " rejected entries");
        assertThat(listings.findById(listing.getId()).orElseThrow()).satisfies(stored -> {
            assertThat(stored.getPhotos()).containsExactlyElementsOf(listing.getPhotos());
            assertThat(stored.getTitle()).isEqualTo(listing.getTitle());
            assertThat(stored.getDescription()).isEqualTo(listing.getDescription());
            assertThat(stored.getCategoryId()).isEqualTo(listing.getCategoryId());
            assertThat(stored.getVersion()).isEqualTo(listing.getVersion());
        });
        assertThat(categories.findById(listing.getCategoryId()).orElseThrow().getListingCount()).isEqualTo(sourceCount);
        assertThat(categories.findById(destination.getId()).orElseThrow().getListingCount()).isZero();
    }

    private Listing listingWithPhotos(String categoryId) {
        String id = UUID.randomUUID().toString();
        return listings.save(new Listing(id, "Original", "Original description", new BigDecimal("100.00"),
                Instant.now(), ListingStatus.ACTIVE, List.of("listings/" + id + "/a", "listings/" + id + "/b"),
                Set.of(PaymentMethod.PAYPAL), "seller", categoryId,
                new HardwareListing(Condition.GOOD, "Rome", new BigDecimal("5.00")), null, Map.of()));
    }

    private ResponseEntity<Map> edit(String subject, String listingId, String categoryId) {
        return edit(subject, listingId, categoryId, List.of());
    }

    private ResponseEntity<Map> edit(String subject, String listingId, String categoryId, List<Map<String, Object>> attributes) {
        return edit(subject, listingId, categoryId, attributes, List.of());
    }

    private ResponseEntity<Map> edit(String subject, String listingId, String categoryId,
            List<Map<String, Object>> attributes, List<String> photos) {
        Map<String, Object> body = Map.of(
                "title", "Edited title",
                "description", "Edited description",
                "photos", photos,
                "attributes", attributes,
                "categoryId", categoryId);
        return http.exchange("/listings/" + listingId, HttpMethod.PUT, authenticated(subject, body), Map.class);
    }

    private AttributeDefinition definition(String id, String name, boolean required) {
        return new AttributeDefinition(id, name, AttributeType.INTEGER, required, List.of(), null, Instant.now());
    }

    private Category category(String name, String root, long listingCount) {
        return categories.save(new Category(UUID.randomUUID().toString(), name, null, List.of(root), List.of(),
                List.of(), "admin", Instant.now(), listingCount));
    }

    private Listing listing(String categoryId, String seller, ListingStatus status) {
        return listings.save(new Listing(UUID.randomUUID().toString(), "Original", "Original description",
                new BigDecimal("100.00"), Instant.now(), status, List.of(), Set.of(PaymentMethod.PAYPAL), seller,
                categoryId, new HardwareListing(Condition.GOOD, "Rome", new BigDecimal("5.00")), null, Map.of()));
    }

    private HttpEntity<?> authenticated(String subject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
