package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.catalog.dto.ArtifactUploadPolicyResponse;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import it.rebyte.commons.messaging.RebyteExchanges;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class ReleaseVersionIT extends MongoTestBase {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ListingRepository listings;
    @Autowired OutboxRepository outbox;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AwsCredentialsProvider credentials;

    @BeforeEach
    void setUpCredentials() {
        org.mockito.Mockito.when(credentials.resolveCredentials())
                .thenReturn(AwsSessionCredentials.create("test-key", "test-secret", "test-token"));
    }

    @Test
    void greaterReleaseBecomesCurrentAndEmitsOneVersionReleasedEvent() {
        String seller = UUID.randomUUID().toString();
        Listing listing = softwareListing(seller, "2.10.0");
        Instant firstRelease = listing.getSoftware().currentVersion().getRelease();

        ResponseEntity<Map> response = release(seller, listing.getId(), "2.11.0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("artifactAvailable", false).doesNotContainKey("artifactKey");
        Listing stored = listings.findById(listing.getId()).orElseThrow();
        assertThat(stored.getSoftware().currentVersion().getNumber()).isEqualTo("2.11.0");
        assertThat(stored.getSoftware().currentVersion().getRelease()).isAfter(firstRelease);
        assertThat(outbox.findAll()).filteredOn(event -> RebyteExchanges.LISTING_VERSION_RELEASED.equals(event.getRoutingKey()))
                .singleElement().satisfies(event -> {
                    var payload = objectMapper.readTree(event.getPayload());
                    assertThat(payload.get("listingId").asText()).isEqualTo(listing.getId());
                    assertThat(payload.get("sellerSub").asText()).isEqualTo(seller);
                    assertThat(payload.get("versionNumber").asText()).isEqualTo("2.11.0");
                    assertThat(payload.get("listingTitle")).isNotNull();
                    assertThat(payload.get("listingTitle").asText()).isEqualTo(listing.getTitle());
                });
    }

    @Test
    void lowerDuplicateAndNonOwnerReleasesAreRefusedWithoutAppending() {
        String seller = UUID.randomUUID().toString();
        Listing listing = softwareListing(seller, "2.10.0");

        assertThat(release(seller, listing.getId(), "2.9.0").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(release(seller, listing.getId(), "2.10.0").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(release("other-user", listing.getId(), "2.11.0").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(listings.findById(listing.getId()).orElseThrow().getSoftware().getVersions()).hasSize(1);
    }

    @Test
    void artifactPolicyIsOwnerOnlyAndScopedToTheListingVersion() throws Exception {
        String seller = UUID.randomUUID().toString();
        Listing listing = softwareListing(seller, "3.0.0");

        ResponseEntity<ArtifactUploadPolicyResponse> response = restTemplate.exchange(
                "/listings/" + listing.getId() + "/versions/3.0.0/artifact-upload-policy",
                HttpMethod.POST, authenticated(seller, null), ArtifactUploadPolicyResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArtifactUploadPolicyResponse policy = response.getBody();
        assertThat(policy.key()).startsWith("software/" + listing.getId() + "/3.0.0/");
        assertThat(objectMapper.readTree(Base64.getDecoder().decode(policy.policy())).get("conditions").toString())
                .contains("application/octet-stream", "104857600", policy.key());
        assertThat(restTemplate.exchange(
                "/listings/" + listing.getId() + "/versions/4.0.0/artifact-upload-policy",
                HttpMethod.POST, authenticated(seller, null), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange(
                "/listings/" + listing.getId() + "/versions/3.0.0/artifact-upload-policy",
                HttpMethod.POST, authenticated("other-user", null), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private Listing softwareListing(String seller, String versionNumber) {
        Version version = new Version(versionNumber, Instant.now().minusSeconds(10), "Current", "software/key");
        return listings.save(new Listing(UUID.randomUUID().toString(), "Utility", "Useful package",
                new BigDecimal("4.99"), Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL),
                seller, "software-category", null, new SoftwareListing(Set.of(Platform.LINUX), List.of(version), null), Map.of()));
    }

    private ResponseEntity<Map> release(String subject, String listingId, String number) {
        return restTemplate.exchange("/listings/" + listingId + "/versions", HttpMethod.POST,
                authenticated(subject, Map.of("number", number, "notes", "Release notes")), Map.class);
    }

    private HttpEntity<?> authenticated(String subject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
