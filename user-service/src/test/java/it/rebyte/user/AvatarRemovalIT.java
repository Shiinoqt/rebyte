package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.rebyte.user.domain.User;
import it.rebyte.user.repository.UserRepository;
import it.rebyte.user.support.LocalAuthTokens;
import it.rebyte.user.support.MySqlTestBase;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * D-01: {@code DELETE /users/me/avatar} against the real user table with the
 * object store stubbed. Idempotency and best-effort deletion are observed
 * through the S3 stand-in, never through a real bucket.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class AvatarRemovalIT extends MySqlTestBase {

    @DynamicPropertySource
    static void issuerMatchesReservedPort(DynamicPropertyRegistry registry) {
        int port = findFreePort();
        registry.add("server.port", () -> port);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:" + port + "/");
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private S3Client s3Client;

    @BeforeEach
    void resetObjectStoreStub() {
        reset(s3Client);
    }

    @Test
    void removalClearsTheAvatarAndDeletesTheStoredObject() {
        String sub = UUID.randomUUID().toString();
        String key = "avatars/" + sub + "/k1";
        String token = provisionWithAvatar(sub, key);

        ResponseEntity<Void> removal = delete(token);

        assertThat(removal.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().key()).isEqualTo(key);
        assertThat(callMe(token).getBody()).containsEntry("avatar", null);
        ResponseEntity<Map> publicProfile = restTemplate.getForEntity("/users/" + sub, Map.class);
        assertThat(publicProfile.getBody()).containsEntry("avatar", null);
        assertThat(userRepository.findById(sub).orElseThrow().getAvatar()).isNull();
    }

    @Test
    void aSecondRemovalIsANoOpThatNeverTouchesTheObjectStore() {
        String sub = UUID.randomUUID().toString();
        String token = provisionWithAvatar(sub, "avatars/" + sub + "/k1");
        assertThat(delete(token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        clearInvocations(s3Client);

        ResponseEntity<Void> second = delete(token);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verifyNoInteractions(s3Client);
    }

    @Test
    void anObjectStoreFailureStillClearsTheAvatarAndSucceeds() {
        String sub = UUID.randomUUID().toString();
        String token = provisionWithAvatar(sub, "avatars/" + sub + "/k1");
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(AwsServiceException.builder().message("delete refused").build());

        ResponseEntity<Void> removal = delete(token);

        assertThat(removal.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
        assertThat(userRepository.findById(sub).orElseThrow().getAvatar()).isNull();
    }

    @Test
    void anonymousRemovalIsRejected() {
        ResponseEntity<String> response = restTemplate.exchange("/users/me/avatar", HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(s3Client);
    }

    private String provisionWithAvatar(String sub, String key) {
        String token = LocalAuthTokens.mint(restTemplate, sub, List.of());
        callMe(token);
        User user = userRepository.findById(sub).orElseThrow();
        user.setAvatar(key);
        userRepository.saveAndFlush(user);
        return token;
    }

    private ResponseEntity<Void> delete(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/users/me/avatar", HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    }

    private ResponseEntity<Map> callMe(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
