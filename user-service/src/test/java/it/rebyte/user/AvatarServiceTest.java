package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import it.rebyte.user.domain.User;
import it.rebyte.user.dto.AvatarUploadPolicyResponse;
import it.rebyte.user.error.AvatarKeyOwnershipException;
import it.rebyte.user.error.AvatarNotUploadedException;
import it.rebyte.user.repository.UserRepository;
import it.rebyte.user.service.AvatarService;
import it.rebyte.commons.media.S3PostPolicySigner;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The avatar policy and confirm decisions (USER-03, D-33 through D-39).
 *
 * <p>Every case here is derived from {@code 01-06-PLAN.md} Task 3 —
 * its {@code <behavior>} list and its {@code <acceptance_criteria>} —
 * not from reading the implementation. The object store is stubbed
 * throughout: these assertions are about the service's decisions, never
 * about the signing arithmetic, which {@code S3PostPolicySignerTest}
 * already pinned against AWS's own published vector.
 *
 * <p>The signer itself is REAL rather than mocked, with static example
 * credentials, because several acceptance criteria are about the content
 * of the emitted policy document — the size cap and the content-type
 * restriction live inside the signed policy, not in service code, and a
 * mocked signer would assert nothing about them.
 */
class AvatarServiceTest {

    private static final String BUCKET = "rebyte-media-test";
    private static final String REGION = "eu-south-1";
    private static final String PREFIX = "avatars/";
    private static final long MAX_SIZE = 524288L;
    private static final long EXPIRY_SECONDS = 300L;
    private static final String SUB = "sub-1";

    private final S3PostPolicySigner signer = new S3PostPolicySigner(
            StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")));
    private final S3Client s3Client = mock(S3Client.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AvatarService service = new AvatarService(
            signer, s3Client, userRepository, objectMapper, BUCKET, REGION, PREFIX, MAX_SIZE, EXPIRY_SECONDS);

    private User user(String avatar) {
        User user = new User(SUB, "handle", Instant.parse("2026-01-01T00:00:00Z"));
        user.setAvatar(avatar);
        return user;
    }

    private JsonNode decodePolicy(AvatarUploadPolicyResponse response) throws Exception {
        byte[] json = Base64.getDecoder().decode(response.policy());
        return objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
    }

    /** The {@code conditions} entry whose first element is {@code name}, e.g. "content-length-range". */
    private JsonNode arrayCondition(JsonNode policy, String name) {
        for (JsonNode condition : policy.get("conditions")) {
            if (condition.isArray() && name.equals(condition.get(0).asText())) {
                return condition;
            }
        }
        return null;
    }

    // ── Requesting an upload policy ──────────────────────────────────────

    @Test
    void policyKeyIsUnderTheCallersOwnPrefix() {
        AvatarUploadPolicyResponse response = service.createUploadPolicy(SUB);

        assertThat(response.key()).startsWith(PREFIX + SUB + "/");
    }

    @Test
    void twoSuccessivePolicyRequestsMintDifferentKeys() {
        String first = service.createUploadPolicy(SUB).key();
        String second = service.createUploadPolicy(SUB).key();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).startsWith(PREFIX + SUB + "/");
        assertThat(second).startsWith(PREFIX + SUB + "/");
    }

    @Test
    void policyResponseCarriesEveryFieldTheBrowserNeeds() {
        AvatarUploadPolicyResponse response = service.createUploadPolicy(SUB);

        assertThat(response.url()).isEqualTo("https://" + BUCKET + ".s3." + REGION + ".amazonaws.com/");
        assertThat(response.key()).isNotBlank();
        assertThat(response.algorithm()).isEqualTo("AWS4-HMAC-SHA256");
        assertThat(response.credential()).contains(REGION).contains("s3").contains("aws4_request");
        assertThat(response.date()).isNotBlank();
        assertThat(response.policy()).isNotBlank();
        assertThat(response.signature()).isNotBlank();
        // Static (non-STS) credentials: the field is omitted rather than sent empty (D-34).
        assertThat(response.securityToken()).isNull();
    }

    @Test
    void policyCapsObjectSizeAtTheConfiguredMaximum() throws Exception {
        JsonNode policy = decodePolicy(service.createUploadPolicy(SUB));

        JsonNode range = arrayCondition(policy, "content-length-range");
        assertThat(range).as("the policy must carry a content-length-range condition").isNotNull();
        assertThat(range.get(2).asLong()).isEqualTo(MAX_SIZE);
    }

    @Test
    void policyRestrictsTheContentTypeToImages() throws Exception {
        JsonNode policy = decodePolicy(service.createUploadPolicy(SUB));

        JsonNode startsWith = arrayCondition(policy, "starts-with");
        assertThat(startsWith).as("the policy must restrict the content type").isNotNull();
        assertThat(startsWith.get(1).asText()).isEqualTo("$Content-Type");
        assertThat(startsWith.get(2).asText()).isEqualTo("image/");
    }

    @Test
    void policyPinsTheExactKeyItWasIssuedFor() throws Exception {
        AvatarUploadPolicyResponse response = service.createUploadPolicy(SUB);
        JsonNode policy = decodePolicy(response);

        JsonNode keyCondition = arrayCondition(policy, "eq");
        assertThat(keyCondition).as("the key must be an exact-match condition").isNotNull();
        assertThat(keyCondition.get(1).asText()).isEqualTo("$key");
        assertThat(keyCondition.get(2).asText()).isEqualTo(response.key());
    }

    // ── Confirming an upload ─────────────────────────────────────────────

    @Test
    void confirmRefusesAKeyOutsideTheCallersPrefix() {
        User user = user(null);

        assertThatThrownBy(() -> service.confirmUpload(user, PREFIX + "someone-else/abc"))
                .isInstanceOf(AvatarKeyOwnershipException.class);

        assertThat(user.getAvatar()).isNull();
        verify(userRepository, never()).saveAndFlush(any());
        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void confirmLeavesTheProfileUnchangedWhenTheObjectDoesNotExist() {
        User user = user("avatars/" + SUB + "/previous");
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not there").build());

        assertThatThrownBy(() -> service.confirmUpload(user, PREFIX + SUB + "/fresh"))
                .isInstanceOf(AvatarNotUploadedException.class);

        assertThat(user.getAvatar()).isEqualTo("avatars/" + SUB + "/previous");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void confirmLeavesTheProfileUnchangedWhenTheMetadataLookupFails() {
        User user = user(null);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(AwsServiceException.builder().message("boom").build());

        assertThatThrownBy(() -> service.confirmUpload(user, PREFIX + SUB + "/fresh"))
                .isInstanceOf(AvatarNotUploadedException.class);

        assertThat(user.getAvatar()).isNull();
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void confirmRefusesAnObjectAboveTheSizeCap() {
        User user = user(null);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(MAX_SIZE + 1).contentType("image/png").build());

        assertThatThrownBy(() -> service.confirmUpload(user, PREFIX + SUB + "/fresh"))
                .isInstanceOf(AvatarNotUploadedException.class);

        assertThat(user.getAvatar()).isNull();
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void confirmRefusesANonImageContentType() {
        User user = user(null);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(1024L).contentType("application/pdf").build());

        assertThatThrownBy(() -> service.confirmUpload(user, PREFIX + SUB + "/fresh"))
                .isInstanceOf(AvatarNotUploadedException.class);

        assertThat(user.getAvatar()).isNull();
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void confirmWritesTheKeyAndDeletesThePreviouslyReferencedObject() {
        String previous = PREFIX + SUB + "/previous";
        String fresh = PREFIX + SUB + "/fresh";
        User user = user(previous);
        stubValidUpload();

        User saved = service.confirmUpload(user, fresh);

        assertThat(saved.getAvatar()).isEqualTo(fresh);
        DeleteObjectRequest deletion = captureDeletion();
        assertThat(deletion.key()).isEqualTo(previous);
        assertThat(deletion.bucket()).isEqualTo(BUCKET);
    }

    @Test
    void aFirstUploadDeletesNothing() {
        User user = user(null);
        stubValidUpload();

        service.confirmUpload(user, PREFIX + SUB + "/fresh");

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void theStoredValueIsTheBareKeyNeverAUrl() {
        User user = user(null);
        stubValidUpload();

        User saved = service.confirmUpload(user, PREFIX + SUB + "/fresh");

        assertThat(saved.getAvatar()).doesNotContain("://").doesNotContain("amazonaws.com")
                .doesNotContain("cloudfront.net").isEqualTo(PREFIX + SUB + "/fresh");
    }

    @Test
    void aDeletionFailureIsLoggedAndDoesNotFailTheRequest() {
        User user = user(PREFIX + SUB + "/previous");
        String fresh = PREFIX + SUB + "/fresh";
        stubValidUpload();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(AwsServiceException.builder().message("delete refused").build());

        Logger logger = (Logger) LoggerFactory.getLogger(AvatarService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            User saved = service.confirmUpload(user, fresh);

            assertThat(saved.getAvatar()).isEqualTo(fresh);
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains(PREFIX + SUB + "/previous");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ── Removing the avatar ──────────────────────────────────────────────

    @Test
    void removeClearsTheReferenceAndDeletesThePreviousObjectOnce() {
        String previous = PREFIX + SUB + "/k1";
        User user = user(previous);
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = service.removeAvatar(user);

        assertThat(saved.getAvatar()).isNull();
        DeleteObjectRequest deletion = captureDeletion();
        assertThat(deletion.key()).isEqualTo(previous);
        assertThat(deletion.bucket()).isEqualTo(BUCKET);
    }

    @Test
    void removeOnAnEmptyAvatarWritesNothingAndCallsNoObjectStore() {
        User user = user(null);

        User result = service.removeAvatar(user);

        assertThat(result.getAvatar()).isNull();
        verify(userRepository, never()).saveAndFlush(any());
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void removeStillClearsTheAvatarWhenTheObjectDeletionFails() {
        String previous = PREFIX + SUB + "/k1";
        User user = user(previous);
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(AwsServiceException.builder().message("delete refused").build());

        User saved = service.removeAvatar(user);

        assertThat(saved.getAvatar()).isNull();
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    private void stubValidUpload() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(
                HeadObjectResponse.builder().contentLength(2048L).contentType("image/png").build());
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private DeleteObjectRequest captureDeletion() {
        var captor = org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        List<DeleteObjectRequest> all = captor.getAllValues();
        assertThat(all).hasSize(1);
        return all.get(0);
    }
}
