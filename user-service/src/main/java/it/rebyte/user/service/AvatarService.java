package it.rebyte.user.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.media.S3PostPolicySigner;
import it.rebyte.user.domain.User;
import it.rebyte.user.dto.AvatarUploadPolicyResponse;
import it.rebyte.user.error.AvatarKeyOwnershipException;
import it.rebyte.user.error.AvatarNotUploadedException;
import it.rebyte.user.repository.UserRepository;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The avatar upload-policy and confirm steps (USER-03, D-33 through D-39).
 *
 * <p><b>Request-policy step (D-33, D-34, D-37).</b> Every call mints a
 * BRAND-NEW key — {@code {avatarsPrefix}{sub}/{uuid}} — never a reuse of
 * the caller's current one. New keys are what make the object immutable:
 * it can be cached indefinitely with no {@code ?v=} parameter, and
 * CloudFront invalidation is never needed (D-36). The returned policy caps
 * the object at {@code maxUploadSizeBytes} (512 KB by default, not 2 MB —
 * D-37) and restricts the content type to images via a {@code starts-with}
 * condition; because the signature covers the WHOLE policy, a client that
 * edits either condition invalidates the signature and the object store
 * refuses the upload before storing anything. No image is ever decoded,
 * resized or re-encoded in this service (D-37): a capped-heap service must
 * never be asked to hold hundreds of MB of raw pixels for a
 * pathologically large-dimension input.
 *
 * <p><b>Confirm step (D-35, D-39).</b> Re-validates the key's ownership,
 * then re-validates size and content type from the object store's OWN
 * metadata — never trusting the client's own claims a second time — and
 * only then writes {@code User.avatar}. This guarantees the column can
 * never point at an object that was never actually confirmed uploaded.
 * The previously referenced object is deleted best-effort afterward: a
 * deletion failure is logged, never surfaced, because a leaked object
 * costs storage whereas failing the user's own successful request over
 * cleanup is worse (D-39; T-06-08, accepted).
 */
@Service
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final String REQUEST_SCOPE = "aws4_request";
    private static final DateTimeFormatter DATE_STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter AMZ_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final S3PostPolicySigner signer;
    private final S3Client s3Client;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final String bucket;
    private final String region;
    private final String avatarsPrefix;
    private final long maxUploadSizeBytes;
    private final long policyExpirySeconds;

    public AvatarService(
            S3PostPolicySigner signer,
            S3Client s3Client,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            @Value("${rebyte.media.bucket}") String bucket,
            @Value("${rebyte.media.region}") String region,
            @Value("${rebyte.media.avatars-prefix:avatars/}") String avatarsPrefix,
            @Value("${rebyte.media.upload.max-size-bytes:524288}") long maxUploadSizeBytes,
            @Value("${rebyte.media.upload.policy-expiry-seconds:300}") long policyExpirySeconds) {
        this.signer = signer;
        this.s3Client = s3Client;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
        this.region = region;
        this.avatarsPrefix = avatarsPrefix;
        this.maxUploadSizeBytes = maxUploadSizeBytes;
        this.policyExpirySeconds = policyExpirySeconds;
    }

    /**
     * Mints a fresh key under the caller's own prefix and returns a signed
     * POST policy for it. The subject is resolved by the controller from
     * the authenticated {@link org.springframework.security.oauth2.jwt.Jwt}
     * — there is no way to request a policy for another user's prefix
     * (D-55's structural ownership, mirrored here — T-06-06).
     */
    public AvatarUploadPolicyResponse createUploadPolicy(String sub) {
        String key = avatarsPrefix + sub + "/" + UUID.randomUUID();

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiration = now.plusSeconds(policyExpirySeconds);
        String dateStamp = DATE_STAMP_FORMAT.format(now.atZone(java.time.ZoneOffset.UTC));
        String amzDate = AMZ_DATE_FORMAT.format(now.atZone(java.time.ZoneOffset.UTC));

        // x-amz-credential is an EXACT-match policy condition, so it must be built with
        // the real access key before signing — the form field the client sends has to
        // be byte-identical to what the signature was computed over.
        AwsCredentials credentials = signer.resolveCredentials();
        String credential = credentials.accessKeyId() + "/" + dateStamp + "/" + region + "/" + SERVICE + "/" + REQUEST_SCOPE;
        String sessionToken = credentials instanceof AwsSessionCredentials sessionCredentials
                ? sessionCredentials.sessionToken()
                : null;

        String policyJson = buildPolicyJson(key, expiration, amzDate, credential, sessionToken);
        S3PostPolicySigner.SignedPolicy signed = signer.signPolicy(policyJson, dateStamp, region);

        String targetUrl = "https://" + bucket + ".s3." + region + ".amazonaws.com/";

        return new AvatarUploadPolicyResponse(
                targetUrl, key, ALGORITHM, credential, amzDate, signed.policyBase64(), signed.signature(),
                signed.sessionToken());
    }

    /**
     * Re-validates ownership and the object's own metadata before writing
     * {@code User.avatar}, then best-effort deletes the previously
     * referenced object.
     */
    @Transactional
    public User confirmUpload(User user, String key) {
        String expectedPrefix = avatarsPrefix + user.getId() + "/";
        if (!key.startsWith(expectedPrefix)) {
            throw new AvatarKeyOwnershipException(key);
        }

        HeadObjectResponse metadata = headObjectOrThrow(key);
        validateMetadataOrThrow(key, metadata);

        String previousKey = user.getAvatar();
        user.setAvatar(key);
        User saved = userRepository.saveAndFlush(user);

        if (previousKey != null && !previousKey.equals(key)) {
            deleteBestEffort(previousKey);
        }
        return saved;
    }

    /**
     * Clears the avatar reference first, then best-effort deletes the object
     * it pointed at. Idempotent: an already empty avatar writes nothing and
     * never reaches the object store. Under a race with {@link
     * #confirmUpload} the last committed write on the user row wins; the
     * object deleted is always the one this call read as current, so a key
     * a concurrent confirm just stored is not the one removed here.
     */
    @Transactional
    public User removeAvatar(User user) {
        String previousKey = user.getAvatar();
        if (previousKey == null) {
            return user;
        }
        user.setAvatar(null);
        User saved = userRepository.saveAndFlush(user);
        deleteBestEffort(previousKey);
        return saved;
    }

    private HeadObjectResponse headObjectOrThrow(String key) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException notFound) {
            throw new AvatarNotUploadedException(key);
        } catch (AwsServiceException lookupFailure) {
            throw new AvatarNotUploadedException(key);
        }
    }

    private void validateMetadataOrThrow(String key, HeadObjectResponse metadata) {
        Long contentLength = metadata.contentLength();
        if (contentLength == null || contentLength <= 0 || contentLength > maxUploadSizeBytes) {
            throw new AvatarNotUploadedException(key);
        }
        String contentType = metadata.contentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new AvatarNotUploadedException(key);
        }
    }

    private void deleteBestEffort(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException deletionFailure) {
            // D-39: a leaked object costs storage; failing an otherwise-successful
            // confirm over cleanup is worse. Logged, never surfaced (T-06-08, accepted).
            log.error("Failed to delete previous avatar object '{}'", key, deletionFailure);
        }
    }

    private String buildPolicyJson(String key, Instant expiration, String amzDate, String credential, String sessionToken) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("expiration", DateTimeFormatter.ISO_INSTANT.format(expiration));

        List<Object> conditions = new ArrayList<>();
        conditions.add(Map.of("bucket", bucket));
        conditions.add(List.of("eq", "$key", key));
        conditions.add(List.of("content-length-range", 1, maxUploadSizeBytes));
        conditions.add(List.of("starts-with", "$Content-Type", "image/"));
        conditions.add(Map.of("x-amz-algorithm", ALGORITHM));
        conditions.add(Map.of("x-amz-credential", credential));
        conditions.add(Map.of("x-amz-date", amzDate));
        if (sessionToken != null) {
            // Temporary/STS credentials (e.g. an instance role): the session token must
            // appear both as a condition here and as a form field, or the upload is
            // refused (01-06-PLAN.md Task 1 action text).
            conditions.add(Map.of("x-amz-security-token", sessionToken));
        }
        policy.put("conditions", conditions);

        try {
            return objectMapper.writeValueAsString(policy);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize the upload policy document", e);
        }
    }
}
