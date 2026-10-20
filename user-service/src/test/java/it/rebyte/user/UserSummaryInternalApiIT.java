package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.rebyte.user.support.LocalAuthTokens;
import it.rebyte.user.support.MySqlTestBase;

/**
 * The internal user-summary read (D-51, D-52): a provisioned sub, a
 * never-provisioned sub returning the synthesized shape rather than a 404,
 * proof that the synthesized body is byte-identical to the materialized
 * one, and proof that a synthesized call never inserts a row.
 *
 * <p>{@code DEFINED_PORT} plus the reserved-port {@code issuer-uri} trick
 * ({@code UserJitProvisioningIT}'s own pattern) even though most of this
 * class calls the unauthenticated internal endpoint: {@link
 * #synthesizedBodyEqualsMaterializedBody} also drives a real authenticated
 * {@code /users/me} call to materialize a row, and that call needs the
 * local-auth issuer discovery to resolve against the actual test port.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class UserSummaryInternalApiIT extends MySqlTestBase {

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
    private DataSource dataSource;

    @Test
    void neverProvisionedSubReturns200SynthesizedShape() {
        String sub = UUID.randomUUID().toString();

        ResponseEntity<Map> response = restTemplate.getForEntity("/internal/users/" + sub, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("sub")).isEqualTo(sub);
        String expectedHandle = "user_" + sub.substring(0, 8).toLowerCase();
        assertThat(body.get("username")).isEqualTo(expectedHandle);
        assertThat(body.get("avatar")).isNull();
        assertThat(body.get("isDeveloper")).isEqualTo(false);
        assertThat(body.get("ratingAvg")).isNull();
        assertThat(body.get("ratingCount")).isEqualTo(0);
        assertThat(body.get("completedSales")).isEqualTo(0);
    }

    @Test
    void synthesizedBodyEqualsMaterializedBody() {
        String sub = UUID.randomUUID().toString();

        ResponseEntity<Map> beforeProvisioning = restTemplate.getForEntity("/internal/users/" + sub, Map.class);

        String token = LocalAuthTokens.mint(restTemplate, sub, List.of());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        restTemplate.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        ResponseEntity<Map> afterProvisioning = restTemplate.getForEntity("/internal/users/" + sub, Map.class);

        assertThat(beforeProvisioning.getBody()).isEqualTo(afterProvisioning.getBody());
    }

    @Test
    void userTableRowCountIsUnchangedBySynthesizedCall() {
        String sub = UUID.randomUUID().toString();
        int before = countUserRows();

        restTemplate.getForEntity("/internal/users/" + sub, Map.class);

        assertThat(countUserRows()).isEqualTo(before);
    }

    private int countUserRows() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `user`", Integer.class);
        return count == null ? 0 : count;
    }
}
