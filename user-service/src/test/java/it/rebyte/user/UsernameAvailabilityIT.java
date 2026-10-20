package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.rebyte.user.domain.User;
import it.rebyte.user.repository.UserRepository;
import it.rebyte.user.support.MySqlTestBase;

/**
 * D-28: the anonymous username check answers 200 with exactly {@code
 * available} and {@code reason}, applies the same rules as the rename, and
 * never writes.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class UsernameAvailabilityIT extends MySqlTestBase {

    private static final String PATH = "/users/username-available?u={u}";

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

    private void seedTakenName() {
        if (!userRepository.existsByUsername("taken_name")) {
            userRepository.saveAndFlush(new User(UUID.randomUUID().toString(), "taken_name", Instant.now()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> check(String candidate) {
        ResponseEntity<Map> response = restTemplate.getForEntity(PATH, Map.class, candidate);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) response.getBody();
    }

    @Test
    void freeNameIsAvailableToAnAnonymousCaller() {
        seedTakenName();

        Map<String, Object> body = check("fresh_name");

        assertThat(body).containsEntry("available", true).containsEntry("reason", null);
    }

    @Test
    void takenNameIsReportedCaseInsensitively() {
        seedTakenName();

        Map<String, Object> body = check("Taken_Name");

        assertThat(body).containsEntry("available", false).containsEntry("reason", "username-taken");
    }

    @Test
    void generatedHandleShapeIsReserved() {
        Map<String, Object> body = check("user_0123abcd");

        assertThat(body).containsEntry("available", false).containsEntry("reason", "reserved-handle");
    }

    @Test
    void ruleViolationsAreAnsweredWith200AndInvalidUsername() {
        assertThat(check("ab")).containsEntry("available", false).containsEntry("reason", "invalid-username");
        assertThat(check("Bad Name!")).containsEntry("available", false).containsEntry("reason", "invalid-username");
    }

    @Test
    void responseCarriesOnlyAvailableAndReason() {
        seedTakenName();

        assertThat(check("taken_name").keySet()).containsExactlyInAnyOrder("available", "reason");
        assertThat(check("fresh_name").keySet()).containsExactlyInAnyOrder("available", "reason");
    }

    @Test
    void checkingNeverCreatesARow() {
        seedTakenName();
        long before = userRepository.count();

        check("fresh_name");
        check("taken_name");
        check("user_0123abcd");
        check("ab");

        assertThat(userRepository.count()).isEqualTo(before);
        assertThat(userRepository.existsByUsername("fresh_name")).isFalse();
    }

    @Test
    void missingParameterReturns400ProblemJson() {
        ResponseEntity<String> response = restTemplate.getForEntity("/users/username-available", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
    }
}
