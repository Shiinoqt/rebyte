package it.rebyte.catalog.service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.dto.PhotoUploadPolicyResponse;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.error.ListingNotOwnedException;
import it.rebyte.catalog.error.PhotoLimitExceededException;
import it.rebyte.catalog.error.PhotoNotUploadedException;
import it.rebyte.catalog.error.PhotoStorageUnavailableException;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.commons.error.DomainException;
import it.rebyte.commons.media.S3PostPolicySigner;
import it.rebyte.commons.messaging.RebyteExchanges;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * S3 metadata is checked before opening a Mongo transaction. Confirm only appends:
 * no object is replaced or deleted, so an aborted write cannot remove a live photo.
 * Any future replacement must defer deletion until after commit (WR-01).
 */
@Service
public class ListingPhotoService {
    private static final Logger log = LoggerFactory.getLogger(ListingPhotoService.class);
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private final S3PostPolicySigner signer;
    private final S3Client s3;
    private final ObjectMapper mapper;
    private final MongoTemplate mongo;
    private final DomainEventRecorder events;
    private final TransactionTemplate transactions;
    private final String bucket;
    private final String region;
    private final String prefix;
    private final long maxSize;
    private final long expiry;

    public ListingPhotoService(S3PostPolicySigner signer, S3Client s3, ObjectMapper mapper,
            MongoTemplate mongo, DomainEventRecorder events, PlatformTransactionManager transactionManager,
            @Value("${rebyte.media.bucket}") String bucket,
            @Value("${rebyte.media.region}") String region,
            @Value("${rebyte.media.listings-prefix}") String prefix,
            @Value("${rebyte.media.upload.max-size-bytes}") long maxSize,
            @Value("${rebyte.media.upload.policy-expiry-seconds}") long expiry) {
        this.signer = signer;
        this.s3 = s3;
        this.mapper = mapper;
        this.mongo = mongo;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.bucket = bucket;
        this.region = region;
        this.prefix = prefix;
        this.maxSize = maxSize;
        this.expiry = expiry;
    }

    public PhotoUploadPolicyResponse createUploadPolicy(String id, String seller) {
        Listing listing = ownedListing(id, seller);
        if (listing.getPhotos().size() >= 5) throw new PhotoLimitExceededException(6);
        String key = prefix + id + "/" + UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now);
        String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
        // Freeze one resolution for both policy conditions and signature, even at STS rotation.
        AwsCredentials credentials = signer.resolveCredentials();
        S3PostPolicySigner fixedSigner = new S3PostPolicySigner(() -> credentials);
        String credential = credentials.accessKeyId() + "/" + date + "/" + region + "/s3/aws4_request";
        String token = credentials instanceof AwsSessionCredentials session ? session.sessionToken() : null;
        List<Object> conditions = new ArrayList<>();
        conditions.add(Map.of("bucket", bucket));
        conditions.add(List.of("eq", "$key", key));
        conditions.add(List.of("content-length-range", 1, maxSize));
        conditions.add(List.of("starts-with", "$Content-Type", "image/"));
        conditions.add(Map.of("x-amz-algorithm", ALGORITHM));
        conditions.add(Map.of("x-amz-credential", credential));
        conditions.add(Map.of("x-amz-date", amzDate));
        if (token != null) conditions.add(Map.of("x-amz-security-token", token));
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("expiration", now.plusSeconds(expiry).toString());
        policy.put("conditions", conditions);
        try {
            var signed = fixedSigner.signPolicy(mapper.writeValueAsString(policy), date, region);
            return new PhotoUploadPolicyResponse("https://" + bucket + ".s3." + region + ".amazonaws.com/",
                    key, ALGORITHM, credential, amzDate, signed.policyBase64(), signed.signature(), token);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize photo upload policy", e);
        }
    }

    public List<String> confirmUpload(String id, String seller, String key) {
        ownedListing(id, seller);
        if (!key.startsWith(prefix + id + "/")) throw new ListingNotOwnedException(id);
        validateObject(key);
        // Like OutboxRelay: retry the whole transaction, never just the failed write.
        for (int attempt = 0; ; attempt++) {
            try {
                return transactions.execute(status -> appendPhoto(id, seller, key));
            } catch (RuntimeException failure) {
                if (attempt >= 9 || !isTransient(failure)) throw failure;
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(5, 41));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while confirming photo", interrupted);
                }
            }
        }
    }

    private List<String> appendPhoto(String id, String seller, String key) {
        Listing current = ownedListing(id, seller);
        if (current.getPhotos().contains(key)) return List.copyOf(current.getPhotos());
        if (current.getPhotos().size() >= 5) throw new PhotoLimitExceededException(6);
        Listing updated = mongo.findAndModify(Query.query(where("id").is(id).and("sellerSub").is(seller)
                        .and("photos.4").exists(false).and("photos").ne(key)),
                new Update().push("photos", key), FindAndModifyOptions.options().returnNew(true), Listing.class);
        if (updated == null) throw new PhotoLimitExceededException(6);
        Instant now = Instant.now();
        events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_UPDATED,
                new ListingChangedEvent(id, updated.getVersion(), now), now);
        return List.copyOf(updated.getPhotos());
    }

    private Listing ownedListing(String id, String seller) {
        Listing listing = mongo.findById(id, Listing.class);
        if (listing == null) throw new ListingNotFoundException(id);
        if (!listing.getSellerSub().equals(seller)) throw new ListingNotOwnedException(id);
        return listing;
    }

    private void validateObject(String key) {
        HeadObjectResponse metadata;
        try {
            metadata = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException missing) {
            throw notUploaded();
        } catch (AwsServiceException failure) {
            if (failure.statusCode() == 404) throw notUploaded();
            log.error("S3 photo lookup failed: status={}, code={}", failure.statusCode(),
                    failure.awsErrorDetails() == null ? "unknown" : failure.awsErrorDetails().errorCode(), failure);
            throw unavailable();
        } catch (SdkClientException failure) {
            log.error("S3 photo lookup failed before a service response", failure);
            throw unavailable();
        }
        if (metadata.contentLength() == null || metadata.contentLength() <= 0 || metadata.contentLength() > maxSize
                || metadata.contentType() == null || !metadata.contentType().startsWith("image/")) {
            throw notUploaded();
        }
    }

    private DomainException notUploaded() {
        return new PhotoNotUploadedException();
    }

    private DomainException unavailable() {
        return new PhotoStorageUnavailableException();
    }

    private boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongoFailure
                    && mongoFailure.hasErrorLabel("TransientTransactionError")) return true;
        }
        return false;
    }
}
