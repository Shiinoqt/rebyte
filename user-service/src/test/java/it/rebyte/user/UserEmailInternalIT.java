package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import it.rebyte.user.support.MySqlTestBase;

/**
 * D-04: the internal email lookup under the local-auth profile resolves any
 * sub to the configured inbox, and answers 404 when none is configured.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@TestPropertySource(properties = "rebyte.user.local-auth-email=verified-inbox@example.test")
class UserEmailInternalIT extends MySqlTestBase {

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
    void configuredAddressResolvesForAnySubAsVerified() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/internal/users/any-sub/email", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("sub")).isEqualTo("any-sub");
        assertThat(body.get("email")).isEqualTo("verified-inbox@example.test");
        assertThat(body.get("verified")).isEqualTo(true);
    }

    @Nested
    @TestPropertySource(properties = "rebyte.user.local-auth-email=")
    class BlankAddress {

        @Test
        void blankConfigurationYields404UserEmailNotFound() {
            ResponseEntity<Map> response = restTemplate.getForEntity("/internal/users/any-sub/email", Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().get("title")).isEqualTo("user-email-not-found");
        }
    }
}
