package it.rebyte.catalog.service;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import it.rebyte.catalog.client.LicenceCheckResponse;
import it.rebyte.catalog.client.OrderServiceClient;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.dto.ArtifactDownloadResponse;
import it.rebyte.catalog.error.ArtifactNotAvailableException;
import it.rebyte.catalog.error.LicenceRequiredException;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.error.ListingModeratedException;
import it.rebyte.catalog.error.OrderServiceUnavailableException;
import it.rebyte.catalog.error.VersionNotFoundException;
import it.rebyte.catalog.repository.ModerationRepository;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Service
public class ArtifactDownloadService {
    private static final Pattern UNSAFE_FILENAME_RUN = Pattern.compile("[^A-Za-z0-9._-]+");
    private final MongoTemplate mongo;
    private final OrderServiceClient orderService;
    private final ModerationRepository moderations;
    private final S3Presigner presigner;
    private final String bucket;
    private final long ttlSeconds;

    public ArtifactDownloadService(MongoTemplate mongo, OrderServiceClient orderService,
            ModerationRepository moderations, S3Presigner presigner,
            @Value("${rebyte.media.bucket}") String bucket,
            @Value("${rebyte.media.download.url-ttl-seconds:120}") long ttlSeconds) {
        this.mongo = mongo;
        this.orderService = orderService;
        this.moderations = moderations;
        this.presigner = presigner;
        this.bucket = bucket;
        this.ttlSeconds = ttlSeconds;
    }

    public ArtifactDownloadResponse download(String listingId, String versionNumber, String callerSub, boolean admin) {
        Listing listing = mongo.findById(listingId, Listing.class);
        if (listing == null || listing.getSoftware() == null) throw new ListingNotFoundException(listingId);
        Version version = listing.getSoftware().getVersions().stream()
                .filter(candidate -> candidate.getNumber().equals(versionNumber)).findFirst()
                .orElseThrow(() -> new VersionNotFoundException(listingId, versionNumber));

        boolean privileged = admin || listing.getSellerSub().equals(callerSub);
        if (!privileged) {
            if (moderations.findFirstByListingId(listingId).isPresent()) throw new ListingModeratedException();
            LicenceCheckResponse check;
            try {
                check = orderService.check(callerSub, listingId);
            } catch (RuntimeException failure) {
                throw new OrderServiceUnavailableException(failure);
            }
            if (check == null || check.active() == null) {
                throw new OrderServiceUnavailableException(new IllegalStateException("Malformed licence response"));
            }
            if (!check.active()) throw new LicenceRequiredException();
        }

        if (version.getArtifactKey() == null) throw new ArtifactNotAvailableException();
        String disposition = "attachment; filename=\"" + filename(listing.getTitle(), versionNumber) + "\"";
        String url = presigner.presignGetObject(request -> request.signatureDuration(Duration.ofSeconds(ttlSeconds))
                .getObjectRequest(get -> get.bucket(bucket).key(version.getArtifactKey())
                        .responseContentDisposition(disposition))).url().toString();
        return new ArtifactDownloadResponse(url, Instant.now().plusSeconds(ttlSeconds));
    }

    private String filename(String title, String versionNumber) {
        String safe = UNSAFE_FILENAME_RUN.matcher(title + "-" + versionNumber).replaceAll("-");
        if (safe.length() > 100) safe = safe.substring(0, 100);
        return safe.isEmpty() ? "artifact" : safe;
    }
}
