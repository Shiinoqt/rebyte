package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class ArtifactConfirmIT extends MongoTestBase {
    @Autowired TestRestTemplate http;
    @Autowired ListingRepository listings;
    @Autowired ObjectMapper mapper;
    @MockitoBean S3Client s3;
    @MockitoBean AwsCredentialsProvider credentials;

    @BeforeEach
    void setUp() {
        when(credentials.resolveCredentials()).thenReturn(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test-key", "test-secret"));
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(1024L).contentType("application/octet-stream").build());
    }

    @Test
    void ownerCanAttachAnArtifactOnceAndPublicVersionResponsesExposeOnlyAvailability() throws Exception {
        Listing listing = softwareListing(UUID.randomUUID().toString(), null);
        String key = key(listing, "1.0.0", UUID.randomUUID().toString());

        ResponseEntity<Map> first = confirm(listing, listing.getSellerSub(), "1.0.0", key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("artifactAvailable", true).doesNotContainKey("artifactKey");
        assertThat(listings.findById(listing.getId()).orElseThrow().getSoftware().getVersions().getFirst().getArtifactKey())
                .isEqualTo(key);

        ResponseEntity<String> second = confirm(listing, listing.getSellerSub(), "1.0.0", key, String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("artifact-already-attached");

        ResponseEntity<String> publicVersions = http.exchange(
                "/listings/" + listing.getId() + "/versions", HttpMethod.GET,
                authenticated(listing.getSellerSub(), null), String.class);
        assertThat(publicVersions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicVersions.getBody()).contains("artifactAvailable").doesNotContain("artifactKey");
    }

    @Test
    void confirmationRejectsNonOwnersWrongPrefixesInvalidObjectsAndUnknownVersions() {
        Listing listing = softwareListing(UUID.randomUUID().toString(), null);
        String key = key(listing, "1.0.0", "bad");

        assertThat(confirm(listing, "other-user", "1.0.0", key, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(confirm(listing, listing.getSellerSub(), "1.0.0", key(listing, "1.1.0", "bad"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(confirm(listing, listing.getSellerSub(), "8.0.0", key(listing, "8.0.0", "bad"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void confirmationRejectsNonUuidSuffixesBeforeCheckingS3() {
        Listing listing = softwareListing(UUID.randomUUID().toString(), null);
        List<String> invalidSuffixes = List.of(
                "arbitrary-text",
                "",
                "123e4567-e89b-12d3-a456-42661417417",
                "123e4567-e89b-12d3-a456-42661417417g",
                "nested/123e4567-e89b-12d3-a456-426614174174",
                "../../other-listing/1.0.0/123e4567-e89b-12d3-a456-426614174174",
                "123e4567-e89b-12d3-a456-426614174174/trailing",
                "123e4567-e89b-12d3-a456-426614174174-extra");
        clearInvocations(s3);

        for (String suffix : invalidSuffixes) {
            ResponseEntity<String> response = confirm(listing, listing.getSellerSub(), "1.0.0",
                    key(listing, "1.0.0", suffix), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.getBody()).contains("artifact-not-uploaded");
        }

        verify(s3, times(0)).headObject(any(HeadObjectRequest.class));
        assertThat(listings.findById(listing.getId()).orElseThrow().getSoftware().getVersions().getFirst()
                .getArtifactKey()).isNull();
    }

    @Test
    void confirmationAcceptsUuidSuffixWithUppercaseHexDigits() {
        Listing listing = softwareListing(UUID.randomUUID().toString(), null);
        String key = key(listing, "1.0.0", "123E4567-E89B-12D3-A456-426614174174");

        assertThat(confirm(listing, listing.getSellerSub(), "1.0.0", key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listings.findById(listing.getId()).orElseThrow().getSoftware().getVersions().getFirst()
                .getArtifactKey()).isEqualTo(key);
    }

    @Test
    void uploadPolicyUsesTheArtifactLimitAndRequiresAnExistingVersion() throws Exception {
        Listing listing = softwareListing(UUID.randomUUID().toString(), null);
        ResponseEntity<String> missing = http.exchange(policyPath(listing, "9.0.0"), HttpMethod.POST,
                authenticated(listing.getSellerSub(), null), String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> response = http.exchange(policyPath(listing, "1.0.0"), HttpMethod.POST,
                authenticated(listing.getSellerSub(), null), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String encoded = (String) response.getBody().get("policy");
        assertThat(mapper.readTree(Base64.getDecoder().decode(encoded)).get("conditions").toString())
                .contains("104857600");
        String generatedKey = (String) response.getBody().get("key");
        assertThat(generatedKey).matches("software/" + listing.getId()
                + "/1\\.0\\.0/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(confirm(listing, listing.getSellerSub(), "1.0.0", generatedKey).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void objectMustBePresentWithinTheInclusiveSizeLimitAndHaveTheExpectedContentType() {
        Listing listing = softwareListing(UUID.randomUUID().toString(), null);
        String key = key(listing, "1.0.0", UUID.randomUUID().toString());
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(104857600L).contentType("application/octet-stream").build());
        assertThat(confirm(listing, listing.getSellerSub(), "1.0.0", key, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Listing overLimit = softwareListing(UUID.randomUUID().toString(), null);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(104857601L).contentType("application/octet-stream").build());
        assertThat(confirm(overLimit, overLimit.getSellerSub(), "1.0.0", key(overLimit, "1.0.0", UUID.randomUUID().toString()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        Listing wrongType = softwareListing(UUID.randomUUID().toString(), null);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(1024L).contentType("text/plain").build());
        assertThat(confirm(wrongType, wrongType.getSellerSub(), "1.0.0", key(wrongType, "1.0.0", UUID.randomUUID().toString()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        Listing missing = softwareListing(UUID.randomUUID().toString(), null);
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        assertThat(confirm(missing, missing.getSellerSub(), "1.0.0", key(missing, "1.0.0", UUID.randomUUID().toString()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        Listing unavailable = softwareListing(UUID.randomUUID().toString(), null);
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(SdkClientException.create("S3 unavailable"));
        assertThat(confirm(unavailable, unavailable.getSellerSub(), "1.0.0", key(unavailable, "1.0.0", UUID.randomUUID().toString()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void concurrentConfirmationsAttachOnlyOneKey() throws Exception {
        Listing listing = softwareListing(UUID.randomUUID().toString(), null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()).stream().map(suffix -> executor.submit(() -> {
                start.await();
                return confirm(listing, listing.getSellerSub(), "1.1.0", key(listing, "1.1.0", suffix), String.class)
                        .getStatusCode();
            })).toList();
            start.countDown();
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        }
        assertThat(listings.findById(listing.getId()).orElseThrow().getSoftware().getVersions().getLast().getArtifactKey())
                .isNotBlank();
    }

    private Listing softwareListing(String seller, String artifactKey) {
        return listings.save(new Listing(UUID.randomUUID().toString(), "Utility", "Useful package",
                new BigDecimal("4.99"), Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL),
                seller, "software-category", null,
                new SoftwareListing(Set.of(Platform.LINUX), List.of(
                        new Version("1.0.0", Instant.now().minusSeconds(60), "First", artifactKey),
                        new Version("1.1.0", Instant.now(), "Latest", null)), null), Map.of()));
    }

    private String key(Listing listing, String version, String suffix) {
        return "software/" + listing.getId() + "/" + version + "/" + suffix;
    }

    private String artifactPath(Listing listing, String version) {
        return "/listings/" + listing.getId() + "/versions/" + version + "/artifact/confirm";
    }

    private String policyPath(Listing listing, String version) {
        return "/listings/" + listing.getId() + "/versions/" + version + "/artifact-upload-policy";
    }

    private ResponseEntity<Map> confirm(Listing listing, String subject, String version, String key) {
        return http.exchange(artifactPath(listing, version), HttpMethod.POST,
                authenticated(subject, Map.of("key", key)), Map.class);
    }

    private <T> ResponseEntity<T> confirm(Listing listing, String subject, String version, String key, Class<T> type) {
        return http.exchange(artifactPath(listing, version), HttpMethod.POST,
                authenticated(subject, Map.of("key", key)), type);
    }

    private HttpEntity<?> authenticated(String subject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
