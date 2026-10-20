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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.rebyte.catalog.client.UserServiceClient;
import it.rebyte.catalog.client.UserSummary;
import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class ListingDetailIT extends MongoTestBase {

    @Autowired TestRestTemplate http;
    @Autowired ListingRepository listings;
    @Autowired CategoryRepository categories;
    @MockitoBean UserServiceClient users;

    @Test
    void anonymousDetailContainsReadableAttributesEmptyHistoryAndExactSellerRating() {
        String seller = UUID.randomUUID().toString();
        String definitionId = UUID.randomUUID().toString();
        AttributeDefinition definition = new AttributeDefinition(definitionId, "vram_gb", AttributeType.INTEGER,
                true, List.of(), "GB", Instant.now());
        Category category = categories.save(new Category(UUID.randomUUID().toString(), "GPU", null,
                List.of("Hardware", "GPU"), List.of(), List.of(definition), "admin", Instant.now(), 1));
        Listing listing = listings.save(new Listing(UUID.randomUUID().toString(), "GPU", "Graphics card",
                new BigDecimal("100.00"), Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL),
                seller, category.getId(), new HardwareListing(Condition.GOOD, "Rome", new BigDecimal("5.00")), null,
                Map.of(definitionId, AttributeValue.ofInteger(16))));
        when(users.getSummary(seller)).thenReturn(new UserSummary(seller, "alice", "avatars/alice.jpg", false,
                4.75, 8));

        ResponseEntity<Map> response = http.getForEntity("/listings/" + listing.getId(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("priceHistory")).isEqualTo(List.of());
        assertThat(body.get("photos")).isEqualTo(List.of());
        assertThat(body.get("photoKeys")).isEqualTo(List.of());
        assertThat((List<?>) body.get("attributes")).singleElement().satisfies(raw -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> attribute = (Map<String, Object>) raw;
            assertThat(attribute).containsEntry("name", "vram_gb").containsEntry("type", "INTEGER")
                    .containsEntry("value", 16).containsEntry("unit", "GB");
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> sellerView = (Map<String, Object>) body.get("seller");
        assertThat(sellerView).containsEntry("username", "alice")
                .containsEntry("avatar", "https://cdn.example.test/avatars/alice.jpg")
                .containsEntry("ratingAvg", 4.75).containsEntry("ratingCount", 8);
    }

    @Test
    void detailReturnsPhotoUrlsAndStoredKeysInTheSameOrder() {
        String seller = UUID.randomUUID().toString();
        String listingId = UUID.randomUUID().toString();
        List<String> photoKeys = List.of("listings/" + listingId + "/a", "listings/" + listingId + "/b");
        Category category = categories.save(new Category(UUID.randomUUID().toString(), "Photos", null,
                List.of("Hardware", "Photos"), List.of(), List.of(), "admin", Instant.now(), 1));
        Listing listing = listings.save(new Listing(listingId, "Item", "Description",
                new BigDecimal("20.00"), Instant.now(), ListingStatus.ACTIVE, photoKeys, Set.of(PaymentMethod.PAYPAL),
                seller, category.getId(), new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, Map.of()));
        when(users.getSummary(seller)).thenReturn(new UserSummary(seller, "alice", "avatars/alice.jpg", false,
                4.75, 8));

        ResponseEntity<Map> response = http.getForEntity("/listings/" + listing.getId(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("photoKeys")).isEqualTo(photoKeys);
        assertThat(body.get("photos")).isEqualTo(List.of(
                "https://cdn.example.test/listings/" + listingId + "/a",
                "https://cdn.example.test/listings/" + listingId + "/b"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sellerView = (Map<String, Object>) body.get("seller");
        assertThat(sellerView).containsEntry("avatar", "https://cdn.example.test/avatars/alice.jpg");
        assertThat(listings.findById(listingId).orElseThrow().getPhotos()).containsExactlyElementsOf(photoKeys);
    }

    @Test
    void noReviewsKeepANullAverageAndSellerFailureDegradesInsteadOfFailingDetail() {
        String seller = UUID.randomUUID().toString();
        Category category = categories.save(new Category(UUID.randomUUID().toString(), "Other", null,
                List.of("Hardware", "Other"), List.of(), List.of(), "admin", Instant.now(), 1));
        Listing listing = listings.save(new Listing(UUID.randomUUID().toString(), "Item", "Description",
                new BigDecimal("20.00"), Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL),
                seller, category.getId(), new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, Map.of()));
        when(users.getSummary(seller)).thenReturn(new UserSummary(seller, "new-seller", null, false, null, 0));

        @SuppressWarnings("unchecked")
        Map<String, Object> sellerView = (Map<String, Object>) http.getForEntity("/listings/" + listing.getId(), Map.class)
                .getBody().get("seller");
        assertThat(sellerView).containsEntry("ratingAvg", null).containsEntry("ratingCount", 0);

        when(users.getSummary(seller)).thenThrow(new IllegalStateException("unavailable"));
        assertThat(http.getForEntity("/listings/" + listing.getId(), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void softwareDetailExposesPlatformsAndVersionViewsWithoutEmbeddingDomainObjects() {
        String seller = UUID.randomUUID().toString();
        Category category = categories.save(new Category(UUID.randomUUID().toString(), "Utilities", null,
                List.of("Software", "Utilities"), List.of(), List.of(), "admin", Instant.now(), 1));
        Listing listing = listings.save(new Listing(UUID.randomUUID().toString(), "Utility", "Description",
                new BigDecimal("20.00"), Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL),
                seller, category.getId(), null, new SoftwareListing(Set.of(Platform.WINDOWS, Platform.LINUX), List.of(
                        new Version("1.0.0", Instant.parse("2026-01-01T00:00:00Z"), "Initial", "artifacts/1.0.0"),
                        new Version("1.1.0", Instant.parse("2026-02-01T00:00:00Z"), "Current", "artifacts/1.1.0")), null),
                Map.of()));
        when(users.getSummary(seller)).thenReturn(new UserSummary(seller, "software-seller", null, false, null, 0));

        ResponseEntity<Map> response = http.getForEntity("/listings/" + listing.getId(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> software = (Map<String, Object>) response.getBody().get("software");
        @SuppressWarnings("unchecked")
        List<String> platforms = (List<String>) software.get("platforms");
        assertThat(platforms).containsExactlyInAnyOrder("WINDOWS", "LINUX");
        assertThat((List<?>) software.get("versions")).anySatisfy(raw -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> version = (Map<String, Object>) raw;
            assertThat(version).containsEntry("number", "1.1.0").containsEntry("current", true)
                    .containsEntry("artifactAvailable", true).doesNotContainKeys("class", "artifactKey");
        });
    }
}
