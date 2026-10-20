package it.rebyte.messaging.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.media.S3PostPolicySigner;
import it.rebyte.messaging.dto.ChatImageUploadPolicyResponse;
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.error.ChatImageNotUploadedException;
import it.rebyte.messaging.error.ChatImageStorageUnavailableException;
import it.rebyte.messaging.error.InvalidChatImageException;
import it.rebyte.messaging.error.UnsupportedImageTypeException;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Service
public class ChatImageService {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final int MAX_IMAGES = 3;
    private static final Pattern UUID_SUFFIX =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final S3PostPolicySigner signer;
    private final ObjectMapper mapper;
    private final String bucket;
    private final String region;
    private final String prefix;
    private final long maxSize;
    private final long expiry;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final Duration urlTtl;
    private final Duration orphanGrace;

    public ChatImageService(S3PostPolicySigner signer, ObjectMapper mapper, S3Client s3, S3Presigner presigner,
            @Value("${rebyte.media.bucket}") String bucket,
            @Value("${rebyte.media.region}") String region,
            @Value("${rebyte.media.chat-prefix}") String prefix,
            @Value("${rebyte.media.chat-upload.max-size-bytes}") long maxSize,
            @Value("${rebyte.media.chat-upload.policy-expiry-seconds}") long expiry,
            @Value("${rebyte.media.chat-url-ttl-seconds}") long urlTtlSeconds,
            @Value("${rebyte.media.chat-orphan-grace}") Duration orphanGrace) {
        this.signer = signer;
        this.mapper = mapper;
        this.bucket = bucket;
        this.region = region;
        this.prefix = prefix;
        this.maxSize = maxSize;
        this.expiry = expiry;
        this.s3 = s3;
        this.presigner = presigner;
        this.urlTtl = Duration.ofSeconds(urlTtlSeconds);
        this.orphanGrace = orphanGrace;
    }

    public ChatImageUploadPolicyResponse createUploadPolicy(String callerSub, String contentType) {
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new UnsupportedImageTypeException();
        }
        String key = prefix + callerSub + "/" + UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now);
        String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
        // Pin policy conditions and signature to the same credentials during STS rotation.
        AwsCredentials credentials = signer.resolveCredentials();
        S3PostPolicySigner fixedSigner = new S3PostPolicySigner(() -> credentials);
        String credential = credentials.accessKeyId() + "/" + date + "/" + region + "/s3/aws4_request";
        String token = credentials instanceof AwsSessionCredentials session ? session.sessionToken() : null;
        List<Object> conditions = new ArrayList<>();
        conditions.add(Map.of("bucket", bucket));
        conditions.add(List.of("eq", "$key", key));
        conditions.add(List.of("content-length-range", 1, maxSize));
        conditions.add(List.of("eq", "$Content-Type", contentType));
        conditions.add(Map.of("x-amz-algorithm", ALGORITHM));
        conditions.add(Map.of("x-amz-credential", credential));
        conditions.add(Map.of("x-amz-date", amzDate));
        if (token != null) conditions.add(Map.of("x-amz-security-token", token));
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("expiration", now.plusSeconds(expiry).toString());
        policy.put("conditions", conditions);
        try {
            var signed = fixedSigner.signPolicy(mapper.writeValueAsString(policy), date, region);
            return new ChatImageUploadPolicyResponse("https://" + bucket + ".s3." + region + ".amazonaws.com/",
                    key, ALGORITHM, credential, amzDate, signed.policyBase64(), signed.signature(), token, contentType);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize chat image upload policy", failure);
        }
    }

    /**
     * D-35: keys must be the caller's own fresh uploads. An object older than the orphan grace period is refused
     * because the sweep may delete it between this check and the message commit.
     */
    public List<String> validateAttachments(String callerSub, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        if (keys.size() > MAX_IMAGES || new HashSet<>(keys).size() != keys.size()) {
            throw new InvalidChatImageException();
        }
        String ownPrefix = prefix + callerSub + "/";
        Instant oldestAccepted = Instant.now().minus(orphanGrace);
        for (String key : keys) {
            if (key == null || !key.startsWith(ownPrefix)
                    || !UUID_SUFFIX.matcher(key.substring(ownPrefix.length())).matches()) {
                throw new InvalidChatImageException();
            }
            HeadObjectResponse head = head(key);
            Long length = head.contentLength();
            if (length == null || length < 1 || length > maxSize || !ALLOWED_TYPES.contains(head.contentType())
                    || (head.lastModified() != null && head.lastModified().isBefore(oldestAccepted))) {
                throw new InvalidChatImageException();
            }
        }
        return List.copyOf(keys);
    }

    /** Generated at render time and never stored; chat images are private and never served through the CDN. */
    public MessageView.ImageView presignedGet(String key) {
        var presigned = presigner.presignGetObject(request -> request.signatureDuration(urlTtl)
                .getObjectRequest(get -> get.bucket(bucket).key(key)));
        return new MessageView.ImageView(presigned.url().toString(), presigned.expiration());
    }

    private HeadObjectResponse head(String key) {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException missing) {
            throw new ChatImageNotUploadedException();
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) {
                throw new ChatImageNotUploadedException();
            }
            throw new ChatImageStorageUnavailableException();
        } catch (SdkException failure) {
            throw new ChatImageStorageUnavailableException();
        }
    }
}
