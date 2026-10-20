package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Instant;
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

import it.rebyte.user.domain.User;
import it.rebyte.user.repository.UserRepository;
import it.rebyte.user.support.LocalAuthTokens;
import it.rebyte.user.support.MySqlTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class PublicProfileFollowIT extends MySqlTestBase {

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

    @Test
    void countsAndCallerStateTrackFollowAndUnfollowWithoutExposingIdentities() {
        String a = seedUser();
        String b = seedUser();
        String c = seedUser();
        String tokenA = LocalAuthTokens.mint(restTemplate, a, List.of());
        String tokenB = LocalAuthTokens.mint(restTemplate, b, List.of());
        String tokenC = LocalAuthTokens.mint(restTemplate, c, List.of());

        assertProfileState(profile(b, tokenA), 0L, 0L, false);

        assertThat(follow(b, tokenA, HttpMethod.PUT).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertProfileState(profile(b, tokenA), 1L, 0L, true);
        assertProfileState(profile(b, tokenC), 1L, 0L, false);
        assertProfileState(profile(b, null), 1L, 0L, false);
        assertProfileState(profile(a, tokenA), 0L, 1L, false);
        assertProfileState(profile(b, tokenB), 1L, 0L, false);
        assertProfileState(profile(b, tokenA), 1L, 0L, true);

        assertThat(follow(b, tokenA, HttpMethod.DELETE).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertProfileState(profile(b, tokenA), 0L, 0L, false);
        assertProfileState(profile(b, null), 0L, 0L, false);
        assertProfileState(profile(a, tokenA), 0L, 0L, false);
    }

    @Test
    void neverProvisionedProfileCarriesCountsAndCallerStateWithoutCreatingUser() {
        String a = seedUser();
        String c = seedUser();
        String unknown = UUID.randomUUID().toString();
        String tokenA = LocalAuthTokens.mint(restTemplate, a, List.of());
        String tokenUnknown = LocalAuthTokens.mint(restTemplate, unknown, List.of());

        assertThat(follow(unknown, tokenA, HttpMethod.PUT).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(follow(c, tokenUnknown, HttpMethod.PUT).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map followerView = profile(unknown, tokenA);
        assertProfileState(followerView, 1L, 1L, true);
        assertThat(followerView).containsEntry("registration", null);
        assertProfileState(profile(unknown, null), 1L, 1L, false);
        assertProfileState(profile(unknown, tokenUnknown), 1L, 1L, false);
        assertThat(userRepository.existsById(unknown)).isFalse();
    }

    private String seedUser() {
        String sub = UUID.randomUUID().toString();
        userRepository.saveAndFlush(new User(sub, "profile_" + sub.replace("-", "").substring(0, 16),
                Instant.now()));
        return sub;
    }

    private Map profile(String sub, String token) {
        ResponseEntity<Map> response = restTemplate.exchange("/users/" + sub, HttpMethod.GET,
                authenticated(token), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("sub", sub);
        return response.getBody();
    }

    private void assertProfileState(Map body, long followers, long following, boolean followedByMe) {
        assertThat(body.keySet()).containsExactlyInAnyOrder(
                "sub", "username", "avatar", "registration", "location", "bio", "isDeveloper",
                "ratingAvg", "ratingCount", "completedSales", "activeListings",
                "followerCount", "followingCount", "followedByMe");
        assertThat(((Number) body.get("followerCount")).longValue()).isEqualTo(followers);
        assertThat(((Number) body.get("followingCount")).longValue()).isEqualTo(following);
        assertThat(body.get("followedByMe")).isEqualTo(followedByMe);
        assertThat(body.get("activeListings")).isEqualTo(List.of());
    }

    private ResponseEntity<Void> follow(String targetSub, String token, HttpMethod method) {
        return restTemplate.exchange("/users/" + targetSub + "/follow", method, authenticated(token), Void.class);
    }

    private HttpEntity<Void> authenticated(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(headers);
    }
}
