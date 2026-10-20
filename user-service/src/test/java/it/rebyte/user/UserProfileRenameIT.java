package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.HashMap;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.rebyte.user.domain.User;
import it.rebyte.user.repository.UserRepository;
import it.rebyte.user.support.LocalAuthTokens;
import it.rebyte.user.support.MySqlTestBase;

import java.time.Instant;

/**
 * D-58: unique-index collisions on rename, using the real MySQL unique key
 * and {@code utf8mb4_0900_ai_ci} collation — a case-only collision must
 * 409 through the real HTTP rename path, and the collation's
 * accent-insensitivity must reject a duplicate at the repository/index
 * level too. Neither can be proven against a mocked repository (see {@code
 * UserProfileServiceTest} for the mocked mutable-field-allowlist coverage
 * instead).
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class UserProfileRenameIT extends MySqlTestBase {

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
    void caseOnlyCollisionReturns409() {
        provisionWithHandle("Seller_case_test");
        String renamerToken = provisionAndGetToken(null);

        ResponseEntity<Map> response = updateUsernameWithToken(renamerToken, "seller_case_test");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("type")).isNotNull();
    }

    @Test
    void accentOnlyDuplicateIsRejectedByTheUniqueIndexAndCollation() {
        // UsernameRules (D-60) already forbids accented characters as
        // rename INPUT — "josé" can never reach PUT /users/me, so the
        // accent-insensitive collation (D-58) can only be proven at the
        // level that actually owns it: the schema/unique-index itself. This
        // proves the deeper guarantee holds even if an accented value ever
        // reached the column by another path (legacy data, a future
        // relaxation of UsernameRules) — not merely that the API rejects
        // accented input, which UsernameRulesTest already covers.
        userRepository.saveAndFlush(new User(UUID.randomUUID().toString(), "jose_accent_test", Instant.now()));

        User accentVariant = new User(UUID.randomUUID().toString(), "josé_accent_test", Instant.now());

        assertThatThrownBy(() -> userRepository.saveAndFlush(accentVariant))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void reservedShapeRenameReturns422() {
        String token = provisionAndGetToken(null);

        ResponseEntity<Map> response = updateUsernameWithToken(token, "user_1a2b3c4d");

        // HttpStatus.UNPROCESSABLE_ENTITY and .UNPROCESSABLE_CONTENT are
        // two distinct enum constants for the SAME numeric status (422,
        // RFC 9110 renamed the reason phrase) — comparing the numeric value
        // avoids depending on which constant the client-side decoder picks.
        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void nonReservedLookalikeRenameSucceeds() {
        String token = provisionAndGetToken(null);

        ResponseEntity<Map> response = updateUsernameWithToken(token, "user_friendly");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("username")).isEqualTo("user_friendly");
    }

    private void provisionWithHandle(String desiredHandle) {
        provisionAndGetToken(desiredHandle);
    }

    private String provisionAndGetToken(String desiredHandle) {
        String sub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, sub, List.of());
        callMe(token); // JIT-provisions the row

        if (desiredHandle != null) {
            ResponseEntity<Map> rename = updateUsernameWithToken(token, desiredHandle);
            assertThat(rename.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        return token;
    }

    private ResponseEntity<Map> updateUsernameWithToken(String token, String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("location", null);
        body.put("bio", null);
        return restTemplate.exchange("/users/me", HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> callMe(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
