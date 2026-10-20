package it.rebyte.catalog.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.dto.ArtifactUploadPolicyResponse;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.error.VersionNotFoundException;
import it.rebyte.commons.media.S3PostPolicySigner;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

@Service
public class SoftwareArtifactService {
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private final S3PostPolicySigner signer;
    private final ObjectMapper mapper;
    private final MongoTemplate mongo;
    private final String bucket;
    private final String region;
    private final String prefix;
    private final long maxSize;
    private final long expiry;

    public SoftwareArtifactService(S3PostPolicySigner signer, ObjectMapper mapper, MongoTemplate mongo,
            @Value("${rebyte.media.bucket}") String bucket, @Value("${rebyte.media.region}") String region,
            @Value("${rebyte.media.software-prefix:software/}") String prefix,
            @Value("${rebyte.media.upload.artifact-max-size-bytes:104857600}") long maxSize,
            @Value("${rebyte.media.upload.policy-expiry-seconds:300}") long expiry) {
        this.signer = signer; this.mapper = mapper; this.mongo = mongo; this.bucket = bucket; this.region = region;
        this.prefix = prefix; this.maxSize = maxSize; this.expiry = expiry;
    }

    public ArtifactUploadPolicyResponse createPolicy(String listingId, String versionNumber) {
        Listing listing = mongo.findById(listingId, Listing.class);
        if (listing == null) throw new ListingNotFoundException(listingId);
        if (listing.getSoftware() == null || listing.getSoftware().getVersions().stream()
                .noneMatch(version -> version.getNumber().equals(versionNumber))) {
            throw new VersionNotFoundException(listingId, versionNumber);
        }
        String key = prefix + listingId + "/" + versionNumber + "/" + UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now);
        String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
        AwsCredentials credentials = signer.resolveCredentials();
        String credential = credentials.accessKeyId() + "/" + date + "/" + region + "/s3/aws4_request";
        String token = credentials instanceof AwsSessionCredentials session ? session.sessionToken() : null;
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("expiration", DateTimeFormatter.ISO_INSTANT.format(now.plusSeconds(expiry)));
        List<Object> conditions = new ArrayList<>();
        conditions.add(Map.of("bucket", bucket));
        conditions.add(List.of("eq", "$key", key));
        conditions.add(Map.of("Content-Type", "application/octet-stream"));
        conditions.add(List.of("content-length-range", 1, maxSize));
        conditions.add(Map.of("x-amz-algorithm", ALGORITHM));
        conditions.add(Map.of("x-amz-credential", credential));
        conditions.add(Map.of("x-amz-date", amzDate));
        if (token != null) conditions.add(Map.of("x-amz-security-token", token));
        policy.put("conditions", conditions);
        try {
            S3PostPolicySigner.SignedPolicy signed = signer.signPolicy(mapper.writeValueAsString(policy), date, region);
            return new ArtifactUploadPolicyResponse("https://" + bucket + ".s3." + region + ".amazonaws.com/", key,
                    ALGORITHM, credential, amzDate, signed.policyBase64(), signed.signature(), signed.sessionToken());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize upload policy", ex);
        }
    }
}
