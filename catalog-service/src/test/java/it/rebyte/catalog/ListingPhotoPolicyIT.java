package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.catalog.domain.*;
import it.rebyte.catalog.dto.PhotoUploadPolicyResponse;
import it.rebyte.catalog.error.PhotoLimitExceededException;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.service.ListingPhotoService;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false", "rebyte.outbox.relay.fixed-delay-ms=3600000"
})
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class ListingPhotoPolicyIT extends MongoTestBase {
    @Autowired TestRestTemplate http;
    @Autowired ListingRepository listings;
    @Autowired ListingPhotoService photos;
    @Autowired OutboxRepository outbox;
    @Autowired ObjectMapper mapper;
    @MockitoBean S3Client s3;
    @MockitoBean AwsCredentialsProvider credentials;
    @MockitoSpyBean DomainEventRecorder events;

    @BeforeEach
    void setUp() {
        when(credentials.resolveCredentials()).thenReturn(AwsSessionCredentials.create("test-key", "test-secret", "test-token"));
        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return HeadObjectResponse.builder().contentLength(100L).contentType("image/png").build();
        });
    }

    @Test
    void policyPinsKeyCapImageTypeAndSessionTokenAndRejectsNonOwner() throws Exception {
        Listing listing = listing(List.of());
        var response = http.exchange(path(listing, "/upload-policy"), HttpMethod.POST,
                request(listing.getSellerSub(), Map.of("key", "listings/someone-else/evil")), PhotoUploadPolicyResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var policy = response.getBody();
        assertThat(policy.key()).startsWith("listings/" + listing.getId() + "/");
        var json = mapper.readTree(Base64.getDecoder().decode(policy.policy()));
        assertThat(json.get("conditions").toString()).contains("2097152", "image/", policy.key(), "test-token");
        assertThat(Instant.parse(json.get("expiration").asText())).isBetween(Instant.now(), Instant.now().plusSeconds(301));
        assertThat(policy.securityToken()).isEqualTo("test-token");
        assertThat(http.exchange(path(listing, "/upload-policy"), HttpMethod.POST,
                request("other", Map.of()), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange(path(listing, "/confirm"), HttpMethod.POST,
                request("other", Map.of("key", policy.key())), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void confirmPreservesOrderIsIdempotentAndRefusesSixthPhoto() {
        Listing listing = listing(List.of());
        String prefix = "listings/" + listing.getId() + "/";
        long before = outbox.count();
        for (int i = 0; i < 5; i++) {
            assertThat(confirm(listing, prefix + i).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(confirm(listing, prefix + 4).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm(listing, prefix + 5).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(listings.findById(listing.getId()).orElseThrow().getPhotos())
                .containsExactly(prefix + 0, prefix + 1, prefix + 2, prefix + 3, prefix + 4);
        assertThat(outbox.count()).isEqualTo(before + 5);
        assertThat(listings.findById(listing.getId()).orElseThrow().getVersion()).isEqualTo(listing.getVersion() + 5);
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void competingConfirmsCannotExceedFivePhotos() throws Exception {
        Listing listing = listing(List.of("a", "b", "c", "d"));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                start.await();
                try {
                    photos.confirmUpload(listing.getId(), listing.getSellerSub(), "listings/" + listing.getId() + "/" + i);
                    return "confirmed";
                } catch (PhotoLimitExceededException limit) {
                    return "full";
                }
            })).toList();
            start.countDown();
            assertThat(List.of(calls.get(0).get(), calls.get(1).get())).containsExactlyInAnyOrder("confirmed", "full");
        }
        assertThat(listings.findById(listing.getId()).orElseThrow().getPhotos()).hasSize(5).startsWith("a", "b", "c", "d");
    }

    @Test
    void wrongPrefixMissingObjectInvalidMetadataAndOperationalErrorsDoNotWrite() {
        Listing listing = listing(List.of());
        String key = "listings/" + listing.getId() + "/one";
        assertThat(confirm(listing, "listings/other/one").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        assertThat(confirm(listing, key).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        doThrow(S3Exception.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build())
                .when(s3).headObject(any(HeadObjectRequest.class));
        assertThat(confirm(listing, key).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        reset(s3);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(100L).contentType("text/plain").build());
        assertThat(confirm(listing, key).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(2097153L).contentType("image/png").build());
        assertThat(confirm(listing, key).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(listings.findById(listing.getId()).orElseThrow().getPhotos()).isEmpty();
    }

    @Test
    void failedOutboxWriteRollsBackPhotoAppend() {
        Listing listing = listing(List.of());
        long before = outbox.count();
        doThrow(new IllegalStateException("outbox unavailable")).when(events).record(any(), any(), any(), any());
        assertThatThrownBy(() -> photos.confirmUpload(listing.getId(), listing.getSellerSub(), "listings/" + listing.getId() + "/one"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(listings.findById(listing.getId()).orElseThrow().getPhotos()).isEmpty();
        assertThat(outbox.count()).isEqualTo(before);
    }

    private Listing listing(List<String> keys) {
        return listings.save(new Listing(UUID.randomUUID().toString(), "Photo test", "Description", BigDecimal.TEN,
                Instant.now(), ListingStatus.ACTIVE, keys, Set.of(PaymentMethod.BANK_TRANSFER), UUID.randomUUID().toString(),
                "category", new HardwareListing(Condition.GOOD, "Roma", BigDecimal.ZERO), null, Map.of()));
    }

    private String path(Listing listing, String suffix) { return "/listings/" + listing.getId() + "/photos" + suffix; }
    private HttpEntity<?> request(String sub, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint(sub));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
    private ResponseEntity<String> confirm(Listing listing, String key) {
        return http.exchange(path(listing, "/confirm"), HttpMethod.POST, request(listing.getSellerSub(), Map.of("key", key)), String.class);
    }
}
