package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.media.S3PostPolicySigner;
import it.rebyte.messaging.dto.ChatImageUploadPolicyResponse;
import it.rebyte.messaging.service.ChatImageService;
import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.TestJwtSupport;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.media.chat-sweep-cron=0 0 0 1 1 *"
})
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class ChatImagePolicyIT extends MessagingTestBase {

    private static final String PATH = "/messages/image-upload-policy";
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired ChatImageService images;
    @MockitoBean AwsCredentialsProvider provider;

    @BeforeEach
    void credentials() {
        when(provider.resolveCredentials()).thenReturn(AwsBasicCredentials.create(
                "AKIAMESSAGINGTEST000", "messaging-test-secret-not-real"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/webp"})
    void policyPinsCallerKeySizeDeclaredTypeAndSigningFields(String contentType) throws Exception {
        var credentials = AwsBasicCredentials.create(
                "AKIAMESSAGINGTEST000", "messaging-test-secret-not-real");
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var response = http.exchange(PATH, HttpMethod.POST, request(Map.of("contentType", contentType)),
                ChatImageUploadPolicyResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatImageUploadPolicyResponse policy = response.getBody();
        assertThat(policy).isNotNull();
        assertThat(policy.key()).startsWith("chat/alice/");
        String suffix = policy.key().substring("chat/alice/".length());
        assertThat(UUID.fromString(suffix).toString()).isEqualTo(suffix);
        assertThat(policy.url()).isEqualTo("https://messaging-test-bucket.s3.eu-south-1.amazonaws.com/");
        assertThat(policy.algorithm()).isEqualTo("AWS4-HMAC-SHA256");
        assertThat(policy.credential()).startsWith("AKIAMESSAGINGTEST000/")
                .endsWith("/eu-south-1/s3/aws4_request");
        assertThat(policy.date()).matches("\\d{8}T\\d{6}Z");
        assertThat(policy.contentType()).isEqualTo(contentType);
        assertThat(policy.securityToken()).isNull();
        var json = mapper.readTree(Base64.getDecoder().decode(policy.policy()));
        var conditions = json.get("conditions");
        assertThat(conditions).contains(
                mapper.valueToTree(Map.of("bucket", "messaging-test-bucket")),
                mapper.valueToTree(List.of("eq", "$key", policy.key())),
                mapper.valueToTree(List.of("content-length-range", 1, 1048576)),
                mapper.valueToTree(List.of("eq", "$Content-Type", contentType)),
                mapper.valueToTree(Map.of("x-amz-algorithm", policy.algorithm())),
                mapper.valueToTree(Map.of("x-amz-credential", policy.credential())),
                mapper.valueToTree(Map.of("x-amz-date", policy.date())));
        assertThat(conditions.size()).isEqualTo(7);
        assertThat(Instant.parse(json.get("expiration").asText())).isBetween(before.plusSeconds(300),
                Instant.now().plusSeconds(300));
        String dateStamp = policy.date().substring(0, 8);
        assertThat(policy.credential()).isEqualTo("AKIAMESSAGINGTEST000/" + dateStamp
                + "/eu-south-1/s3/aws4_request");
        assertThat(policy.signature()).isEqualTo(new S3PostPolicySigner(() -> credentials)
                .signPolicy(json.toString(), dateStamp, "eu-south-1").signature());
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "text/html"})
    void unsupportedTypesReturnDomainProblem(String contentType) throws Exception {
        var response = http.exchange(PATH, HttpMethod.POST, request(Map.of("contentType", contentType)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var problem = mapper.readTree(response.getBody());
        assertThat(problem.get("title").asText()).isEqualTo("unsupported-image-type");
        assertThat(problem.get("type").asText()).endsWith("unsupported-image-type");
    }

    @Test
    void missingOrBlankContentTypeReturnsBadRequest() {
        for (Map<String, String> body : List.of(Map.<String, String>of(), Map.of("contentType", " "))) {
            assertThat(http.exchange(PATH, HttpMethod.POST, request(body), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void anonymousUploadPolicyRequestIsUnauthorized() {
        assertThat(http.postForEntity(PATH, Map.of("contentType", "image/png"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sessionCredentialsStayIdenticalAcrossConditionsResponseAndSignature() throws Exception {
        var credentials = AwsSessionCredentials.create("session-key", "session-secret", "session-token");
        when(provider.resolveCredentials()).thenReturn(credentials,
                AwsSessionCredentials.create("rotated-key", "rotated-secret", "rotated-token"));

        ChatImageUploadPolicyResponse policy = images.createUploadPolicy("alice", "image/png");
        var json = mapper.readTree(Base64.getDecoder().decode(policy.policy()));
        assertThat(policy.securityToken()).isEqualTo("session-token");
        assertThat(policy.credential()).startsWith("session-key/");
        assertThat(json.get("conditions")).contains(mapper.valueToTree(Map.of("x-amz-security-token", "session-token")));
        assertThat(policy.signature()).isEqualTo(new S3PostPolicySigner(() -> credentials)
                .signPolicy(json.toString(), policy.date().substring(0, 8), "eu-south-1").signature());
    }

    private HttpEntity<?> request(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint("alice"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
