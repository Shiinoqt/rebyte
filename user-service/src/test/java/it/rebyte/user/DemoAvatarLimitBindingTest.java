package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.media.S3PostPolicySigner;
import it.rebyte.user.domain.User;
import it.rebyte.user.error.AvatarNotUploadedException;
import it.rebyte.user.repository.UserRepository;
import it.rebyte.user.service.AvatarService;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

class DemoAvatarLimitBindingTest {

    private static final long DEMO_MAX = 262144L;

    private final S3Client s3 = mock(S3Client.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AvatarService.class)
            .withPropertyValues("MEDIA_BUCKET=demo-media", "MEDIA_REGION=eu-south-1")
            .withBean(S3PostPolicySigner.class, () -> new S3PostPolicySigner(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("demo-access", "demo-secret"))))
            .withBean(S3Client.class, () -> s3)
            .withBean(UserRepository.class, () -> users)
            .withBean(ObjectMapper.class, () -> mapper);

    @Test
    void localDefaultKeepsItsExistingSignedPolicyCap() {
        assertPolicyCap(runner, 524288L);
    }

    @Test
    void demoEnvironmentOverrideReachesTheSignedPolicy() {
        assertPolicyCap(runner.withPropertyValues("MEDIA_AVATAR_MAX_SIZE_BYTES=" + DEMO_MAX), DEMO_MAX);
    }

    @ParameterizedTest
    @CsvSource({ "0,false", "262144,true", "262145,false" })
    void confirmationUsesTheSameConfiguredBoundary(long size, boolean accepted) {
        runner.withPropertyValues("MEDIA_AVATAR_MAX_SIZE_BYTES=" + DEMO_MAX).run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(AvatarService.class);
            var user = new User("buyer-demo", "buyer", Instant.now());
            String previous = "avatars/buyer-demo/previous";
            String fresh = "avatars/buyer-demo/fresh";
            user.setAvatar(previous);
            when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                    HeadObjectResponse.builder().contentLength(size).contentType("image/png").build());
            if (accepted) {
                when(users.saveAndFlush(any())).thenReturn(user);
                assertThat(service.confirmUpload(user, fresh)).isSameAs(user);
                assertThat(user.getAvatar()).isEqualTo(fresh);
                verify(users).saveAndFlush(user);
            } else {
                assertThatThrownBy(() -> service.confirmUpload(user, fresh))
                        .isInstanceOf(AvatarNotUploadedException.class);
                assertThat(user.getAvatar()).isEqualTo(previous);
                verify(users, never()).saveAndFlush(any());
                verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
            }
        });
    }

    private void assertPolicyCap(ApplicationContextRunner contextRunner, long expectedMax) {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var response = context.getBean(AvatarService.class).createUploadPolicy("buyer-demo");
            JsonNode policy = mapper.readTree(new String(Base64.getDecoder().decode(response.policy()),
                    StandardCharsets.UTF_8));
            JsonNode range = null;
            for (JsonNode condition : policy.get("conditions")) {
                if (condition.isArray() && "content-length-range".equals(condition.get(0).asText())) range = condition;
            }
            assertThat(range).isNotNull();
            assertThat(range.get(1).asLong()).isEqualTo(1);
            assertThat(range.get(2).asLong()).isEqualTo(expectedMax);
            verifyNoInteractions(s3, users);
        });
    }
}
