package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.error.PhotoNotUploadedException;
import it.rebyte.catalog.error.ArtifactNotUploadedException;
import it.rebyte.catalog.service.ArtifactConfirmService;
import it.rebyte.catalog.service.ListingCache;
import it.rebyte.catalog.service.ListingPhotoService;
import it.rebyte.catalog.service.SoftwareArtifactService;
import it.rebyte.commons.media.S3PostPolicySigner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

class DemoMediaLimitBindingTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final S3Client s3 = mock(S3Client.class);
    private final DomainEventRecorder events = mock(DomainEventRecorder.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ListingCache cache = mock(ListingCache.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ListingPhotoService.class, SoftwareArtifactService.class, ArtifactConfirmService.class)
            .withPropertyValues("MEDIA_BUCKET=demo-media", "MEDIA_REGION=eu-south-1")
            .withBean(S3PostPolicySigner.class, () -> new S3PostPolicySigner(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("demo-access", "demo-secret"))))
            .withBean(S3Client.class, () -> s3)
            .withBean(ObjectMapper.class, () -> mapper)
            .withBean(MongoTemplate.class, () -> mongo)
            .withBean(DomainEventRecorder.class, () -> events)
            .withBean(ListingCache.class, () -> cache)
            .withBean(PlatformTransactionManager.class, () -> transactions);

    @Test
    void localDefaultsReachBothSignedPolicies() {
        assertPolicies(runner, 2097152L, 104857600L);
    }

    @Test
    void demoOverridesReachBothSignedPolicies() {
        assertPolicies(runner.withPropertyValues("MEDIA_PHOTO_MAX_SIZE_BYTES=1048576",
                "MEDIA_ARTIFACT_MAX_SIZE_BYTES=10485760"), 1048576L, 10485760L);
    }

    @ParameterizedTest
    @CsvSource({ "0,false", "1048576,true", "1048577,false" })
    void photoConfirmationUsesTheConfiguredBoundary(long size, boolean accepted) {
        runner.withPropertyValues("MEDIA_PHOTO_MAX_SIZE_BYTES=1048576").run(context -> {
            assertThat(context).hasNotFailed();
            String key = "listings/listing-demo/fresh";
            Listing original = listing(List.of(), null);
            when(mongo.findById("listing-demo", Listing.class)).thenReturn(original);
            when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                    HeadObjectResponse.builder().contentLength(size).contentType("image/png").build());
            var service = context.getBean(ListingPhotoService.class);
            if (accepted) {
                when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
                when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                        eq(Listing.class))).thenReturn(listing(List.of(key), null));
                assertThat(service.confirmUpload("listing-demo", "seller-demo", key)).containsExactly(key);
                verify(transactions).commit(any());
            } else {
                assertThatThrownBy(() -> service.confirmUpload("listing-demo", "seller-demo", key))
                        .isInstanceOf(PhotoNotUploadedException.class);
                assertThat(original.getPhotos()).isEmpty();
                verify(mongo, never()).findAndModify(any(Query.class), any(Update.class),
                        any(FindAndModifyOptions.class), eq(Listing.class));
                verifyNoInteractions(transactions, events);
            }
        });
    }

    @ParameterizedTest
    @CsvSource({ "0,false", "10485760,true", "10485761,false" })
    void artifactConfirmationUsesTheConfiguredBoundary(long size, boolean accepted) {
        runner.withPropertyValues("MEDIA_ARTIFACT_MAX_SIZE_BYTES=10485760").run(context -> {
            assertThat(context).hasNotFailed();
            String key = "software/listing-demo/1.0.0/123e4567-e89b-12d3-a456-426614174174";
            Listing original = listing(List.of(), null);
            when(mongo.findById("listing-demo", Listing.class)).thenReturn(original);
            when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                    HeadObjectResponse.builder().contentLength(size).contentType("application/octet-stream").build());
            var service = context.getBean(ArtifactConfirmService.class);
            if (accepted) {
                when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
                when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                        eq(Listing.class))).thenReturn(listing(List.of(), key));
                assertThat(service.confirm("listing-demo", "1.0.0", "seller-demo", key).artifactAvailable()).isTrue();
                verify(transactions).commit(any());
                verify(cache).evict("listing-demo");
            } else {
                assertThatThrownBy(() -> service.confirm("listing-demo", "1.0.0", "seller-demo", key))
                        .isInstanceOf(ArtifactNotUploadedException.class);
                assertThat(original.getSoftware().getVersions().getFirst().getArtifactKey()).isNull();
                verify(mongo, never()).findAndModify(any(Query.class), any(Update.class),
                        any(FindAndModifyOptions.class), eq(Listing.class));
                verifyNoInteractions(transactions, cache);
            }
        });
    }

    private void assertPolicies(ApplicationContextRunner configured, long photoMax, long artifactMax) {
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            Listing listing = listing(List.of(), null);
            when(mongo.findById("listing-demo", Listing.class)).thenReturn(listing);
            assertPolicyCap(context.getBean(ListingPhotoService.class)
                    .createUploadPolicy("listing-demo", "seller-demo").policy(), photoMax);
            assertPolicyCap(context.getBean(SoftwareArtifactService.class)
                    .createPolicy("listing-demo", "1.0.0").policy(), artifactMax);
            verifyNoInteractions(s3, transactions, events);
        });
    }

    private void assertPolicyCap(String encoded, long expected) throws Exception {
        JsonNode policy = mapper.readTree(Base64.getDecoder().decode(encoded));
        JsonNode range = null;
        for (JsonNode condition : policy.get("conditions")) {
            if (condition.isArray() && "content-length-range".equals(condition.get(0).asText())) range = condition;
        }
        assertThat(range).isNotNull();
        assertThat(range.get(1).asLong()).isEqualTo(1);
        assertThat(range.get(2).asLong()).isEqualTo(expected);
    }

    private Listing listing(List<String> photos, String artifactKey) {
        Listing result = new Listing("listing-demo", "Utility", "Useful package", new BigDecimal("4.99"),
                Instant.now(), ListingStatus.ACTIVE, photos, Set.of(PaymentMethod.PAYPAL), "seller-demo",
                "software-category", null, new SoftwareListing(Set.of(Platform.LINUX),
                        List.of(new Version("1.0.0", Instant.now(), "First", artifactKey)), null), Map.of());
        ReflectionTestUtils.setField(result, "version", 1L);
        return result;
    }
}
