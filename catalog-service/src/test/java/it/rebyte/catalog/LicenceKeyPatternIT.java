package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.data.mongodb.core.MongoTemplate;
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
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.dto.ListingAvailability;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import it.rebyte.commons.license.LicenseKeyPattern;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class LicenceKeyPatternIT extends MongoTestBase {

    @Autowired TestRestTemplate http;
    @Autowired CategoryRepository categories;
    @Autowired ListingRepository listings;
    @Autowired MongoTemplate mongo;
    @MockitoBean UserServiceClient users;

    @Test
    void publishExposesEffectivePatternOnlyOnInternalCheckoutRead() throws Exception {
        String seller = UUID.randomUUID().toString();
        when(users.getSummary(seller)).thenReturn(new UserSummary(seller, "developer", null, true, null, 0));
        Category category = softwareLeaf();

        ResponseEntity<Map> custom = publish(seller, category.getId(), "ACME-########-########");
        assertThat(custom.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String customId = String.valueOf(custom.getBody().get("id"));
        assertThat(internal(customId).getBody().licenceKeyPattern()).isEqualTo("ACME-########-########");
        String publicJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                http.getForEntity("/listings/" + customId, Map.class).getBody());
        assertThat(publicJson).doesNotContain("licenceKeyPattern");

        ResponseEntity<Map> defaultPattern = publish(seller, category.getId(), null);
        assertThat(defaultPattern.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(internal(String.valueOf(defaultPattern.getBody().get("id"))).getBody().licenceKeyPattern())
                .isEqualTo(LicenseKeyPattern.DEFAULT);
    }

    @Test
    void rejectsWeakPatternWithoutCreatingListingAndHardwareHasNoPattern() {
        String seller = UUID.randomUUID().toString();
        when(users.getSummary(seller)).thenReturn(new UserSummary(seller, "developer", null, true, null, 0));
        Category software = softwareLeaf();
        long before = listings.count();

        ResponseEntity<Map> invalid = publish(seller, software.getId(), "ACME-###############");

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(invalid.getBody()).containsValue("invalid-licence-key-pattern");
        assertThat(listings.count()).isEqualTo(before);

        Category hardware = categories.save(new Category(UUID.randomUUID().toString(), "Hardware-" + UUID.randomUUID(), null,
                List.of("Hardware"), List.of(), List.of(), "admin", Instant.now(), 0L));
        Listing listing = listings.save(new Listing(UUID.randomUUID().toString(), "GPU", "Hardware listing",
                BigDecimal.TEN, Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL), seller,
                hardware.getId(), new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, Map.of()));
        assertThat(internal(listing.getId()).getBody().licenceKeyPattern()).isNull();
    }

    @Test
    void editValidatesPatternPreservesOmittedValuesAndDefaultsLegacyListings() {
        String seller = UUID.randomUUID().toString();
        Category software = softwareLeaf();
        Listing listing = softwareListing(seller, software.getId(), null);
        mongo.insert(listing);

        assertThat(edit(seller, listing.getId(), "EDIT-########-########").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(internal(listing.getId()).getBody().licenceKeyPattern()).isEqualTo("EDIT-########-########");

        assertThat(edit(seller, listing.getId(), "EDIT-###############").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(internal(listing.getId()).getBody().licenceKeyPattern()).isEqualTo("EDIT-########-########");
        assertThat(edit(seller, listing.getId(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(internal(listing.getId()).getBody().licenceKeyPattern()).isEqualTo("EDIT-########-########");

        Listing legacy = softwareListing(seller, software.getId(), null);
        mongo.insert(legacy);
        assertThat(internal(legacy.getId()).getBody().licenceKeyPattern()).isEqualTo(LicenseKeyPattern.DEFAULT);

        Category hardware = categories.save(new Category(UUID.randomUUID().toString(), "Hardware-" + UUID.randomUUID(), null,
                List.of("Hardware"), List.of(), List.of(), "admin", Instant.now(), 0L));
        Listing hardwareListing = new Listing(UUID.randomUUID().toString(), "GPU", "Hardware listing", BigDecimal.TEN,
                Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL), seller, hardware.getId(),
                new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, Map.of());
        mongo.insert(hardwareListing);
        assertThat(edit(seller, hardwareListing.getId(), "EDIT-########-########").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    private ResponseEntity<Map> publish(String seller, String categoryId, String pattern) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Software " + UUID.randomUUID());
        body.put("description", "A useful software package");
        body.put("price", "9.99");
        body.put("categoryId", categoryId);
        body.put("photos", List.of());
        body.put("paymentMethods", List.of("PAYPAL"));
        body.put("platforms", List.of("LINUX"));
        body.put("versionNumber", "1.0.0");
        body.put("notes", "Initial release");
        body.put("attributes", List.of());
        if (pattern != null) body.put("licenceKeyPattern", pattern);
        return http.exchange("/listings/software", HttpMethod.POST, authenticated(seller, body), Map.class);
    }

    private ResponseEntity<ListingAvailability> internal(String id) {
        return http.getForEntity("/internal/listings/" + id, ListingAvailability.class);
    }

    private ResponseEntity<Map> edit(String seller, String id, String pattern) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Edited software");
        body.put("description", "Edited description");
        body.put("photos", List.of());
        body.put("attributes", List.of());
        if (pattern != null) body.put("licenceKeyPattern", pattern);
        return http.exchange("/listings/" + id, HttpMethod.PUT, authenticated(seller, body), Map.class);
    }

    private Listing softwareListing(String seller, String categoryId, String pattern) {
        Version version = new Version("1.0.0", Instant.now(), "Initial release", null);
        return new Listing(UUID.randomUUID().toString(), "Software", "Software listing", BigDecimal.TEN,
                Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL), seller, categoryId,
                null, new SoftwareListing(Set.of(Platform.LINUX), List.of(version), pattern), Map.of());
    }

    private HttpEntity<?> authenticated(String subject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Category softwareLeaf() {
        return categories.save(new Category(UUID.randomUUID().toString(), "Software-" + UUID.randomUUID(), null,
                List.of("Software"), List.of(), List.of(), "admin", Instant.now(), 0L));
    }
}
