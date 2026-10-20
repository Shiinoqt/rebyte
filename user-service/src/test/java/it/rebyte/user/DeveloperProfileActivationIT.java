package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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

import it.rebyte.user.support.LocalAuthTokens;
import it.rebyte.user.support.MySqlTestBase;

/**
 * USER-05 end to end, real HTTP: {@code POST /users/me/developer-profile}
 * activates, a repeat call is idempotent (same {@code activatedAt}), and
 * the activation is visible on {@code GET /users/{sub}}'s {@code
 * isDeveloper} member — the full chain the plan's must-have truth
 * describes, which {@code DeveloperProfileServiceTest} (mocked repository)
 * and {@code PublicProfileServiceTest} (mocked repository) each cover only
 * in isolation.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class DeveloperProfileActivationIT extends MySqlTestBase {

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

    @Test
    void activationIsIdempotentAndVisibleOnThePublicProfile() {
        String sub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, sub, List.of());
        callMe(token); // JIT-provisions the user row first

        // GET /users/{sub} is "public" in the D-55 ownership sense (no
        // owner-check; any authenticated caller can read anyone's profile)
        // — it is NOT unauthenticated at the service level, since
        // SecurityConfig requires a valid token on every /users/** route.
        ResponseEntity<Map> publicProfileBefore = getPublicProfile(token, sub);
        assertThat(publicProfileBefore.getBody().get("isDeveloper")).isEqualTo(false);

        ResponseEntity<Map> first = activate(token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object firstActivatedAt = first.getBody().get("activatedAt");
        assertThat(firstActivatedAt).isNotNull();

        ResponseEntity<Map> second = activate(token);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("activatedAt")).isEqualTo(firstActivatedAt);

        ResponseEntity<Map> publicProfileAfter = getPublicProfile(token, sub);
        assertThat(publicProfileAfter.getBody().get("isDeveloper")).isEqualTo(true);
    }

    private ResponseEntity<Map> activate(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(
                "/users/me/developer-profile", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> getPublicProfile(String token, String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/users/" + sub, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> callMe(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
