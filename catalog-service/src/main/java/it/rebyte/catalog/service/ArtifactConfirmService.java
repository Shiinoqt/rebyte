package it.rebyte.catalog.service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import com.mongodb.MongoException;
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

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.dto.VersionResponse;
import it.rebyte.catalog.error.ArtifactAlreadyAttachedException;
import it.rebyte.catalog.error.ArtifactNotUploadedException;
import it.rebyte.catalog.error.ArtifactStorageUnavailableException;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.error.ListingNotOwnedException;
import it.rebyte.catalog.error.VersionNotFoundException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Service
public class ArtifactConfirmService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactConfirmService.class);
    private static final Pattern UUID_SUFFIX = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final MongoTemplate mongo;
    private final S3Client s3;
    private final ListingCache cache;
    private final TransactionTemplate transactions;
    private final String bucket;
    private final String prefix;
    private final long maxSize;

    public ArtifactConfirmService(MongoTemplate mongo, S3Client s3, ListingCache cache,
            PlatformTransactionManager transactionManager, @Value("${rebyte.media.bucket}") String bucket,
            @Value("${rebyte.media.software-prefix:software/}") String prefix,
            @Value("${rebyte.media.upload.artifact-max-size-bytes:104857600}") long maxSize) {
        this.mongo = mongo;
        this.s3 = s3;
        this.cache = cache;
        this.transactions = new TransactionTemplate(transactionManager);
        this.bucket = bucket;
        this.prefix = prefix;
        this.maxSize = maxSize;
    }

    public VersionResponse confirm(String listingId, String versionNumber, String seller, String key) {
        Listing listing = mongo.findById(listingId, Listing.class);
        if (listing == null || listing.getSoftware() == null) throw new ListingNotFoundException(listingId);
        if (!listing.getSellerSub().equals(seller)) throw new ListingNotOwnedException(listingId);
        Version version = listing.getSoftware().getVersions().stream()
                .filter(candidate -> candidate.getNumber().equals(versionNumber)).findFirst()
                .orElseThrow(() -> new VersionNotFoundException(listingId, versionNumber));
        if (version.getArtifactKey() != null) throw new ArtifactAlreadyAttachedException();
        String expectedPrefix = prefix + listingId + "/" + versionNumber + "/";
        if (!key.startsWith(expectedPrefix)
                || !UUID_SUFFIX.matcher(key.substring(expectedPrefix.length())).matches()) {
            throw new ArtifactNotUploadedException();
        }
        validateObject(key);

        Listing updated = attach(listingId, versionNumber, seller, key);
        if (updated == null) throw new ArtifactAlreadyAttachedException();
        cache.evict(listingId);
        Version attached = updated.getSoftware().getVersions().stream()
                .filter(candidate -> candidate.getNumber().equals(versionNumber)).findFirst()
                .orElseThrow(() -> new VersionNotFoundException(listingId, versionNumber));
        return VersionResponse.from(attached,
                updated.getSoftware().currentVersion().getNumber().equals(versionNumber));
    }

    private Listing attach(String listingId, String versionNumber, String seller, String key) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transactions.execute(status -> mongo.findAndModify(
                        Query.query(where("id").is(listingId).and("sellerSub").is(seller)
                                .and("software.versions").elemMatch(
                                        where("number").is(versionNumber).and("artifactKey").is(null))),
                        new Update().set("software.versions.$.artifactKey", key),
                        FindAndModifyOptions.options().returnNew(true), Listing.class));
            } catch (RuntimeException failure) {
                if (attempt >= 9 || !isTransient(failure)) throw failure;
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(5, 41));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while confirming artifact", interrupted);
                }
            }
        }
    }

    private boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongoFailure
                    && mongoFailure.hasErrorLabel("TransientTransactionError")) return true;
        }
        return false;
    }

    private void validateObject(String key) {
        HeadObjectResponse metadata;
        try {
            metadata = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException missing) {
            throw new ArtifactNotUploadedException();
        } catch (AwsServiceException failure) {
            if (failure.statusCode() == 404) throw new ArtifactNotUploadedException();
            log.error("S3 artifact lookup failed: status={}, code={}", failure.statusCode(),
                    failure.awsErrorDetails() == null ? "unknown" : failure.awsErrorDetails().errorCode());
            throw new ArtifactStorageUnavailableException();
        } catch (SdkClientException failure) {
            log.error("S3 artifact lookup failed before a service response");
            throw new ArtifactStorageUnavailableException();
        }
        if (metadata.contentLength() == null || metadata.contentLength() < 1 || metadata.contentLength() > maxSize
                || !"application/octet-stream".equals(metadata.contentType())) {
            throw new ArtifactNotUploadedException();
        }
    }
}
