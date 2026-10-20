package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
 * IDENT-04, D-08, D-09, D-10: self-healing admin-row JIT provisioning on any
 * authenticated request carrying {@code ROLE_ADMIN}.
 *
 * <p>The local-auth minter ({@code LocalAuthTokenController#mint})
 * coalesces an omitted or explicit-null {@code groups} request field into
 * the SAME empty {@code cognito:groups: []} JWT claim before signing, so
 * "absent", "null" and "empty" are observationally identical through this
 * minter — proving the empty-list case here also covers "absent"/"null" at
 * the integration level. The claim-shape distinction itself (null vs
 * missing vs empty JWT claim producing zero authorities) is already
 * unit-tested directly against a hand-built {@link
 * org.springframework.security.oauth2.jwt.Jwt} in 01-01's
 * {@code GroupsToAuthoritiesConverterTest}, which does not go through this
 * minter and CAN construct those distinct claim shapes.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class AdminJitProvisioningIT extends MySqlTestBase {

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
    void adminGroupFirstRequestCreatesRowKeyedBySameSub() {
        String sub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, sub, List.of("ADMIN"));

        ResponseEntity<Map> response = callMe(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminRowCountFor(sub)).isEqualTo(1);
        assertThat(adminIdFor(sub)).isEqualTo(sub);
    }

    @Test
    void concurrentAdminFirstRequestsLeaveExactlyOneRow() {
        String sub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, sub, List.of("ADMIN"));

        CompletableFuture<ResponseEntity<Map>> requestA = CompletableFuture.supplyAsync(() -> callMe(token));
        CompletableFuture<ResponseEntity<Map>> requestB = CompletableFuture.supplyAsync(() -> callMe(token));
        CompletableFuture.allOf(requestA, requestB).join();

        assertThat(requestA.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(requestB.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminRowCountFor(sub)).isEqualTo(1);
    }

    @Test
    void emptyGroupsClaimCreatesNoAdminRowAndStillSucceeds() {
        String sub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, sub, List.of());

        ResponseEntity<Map> response = callMe(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminRowCountFor(sub)).isZero();
    }

    @Test
    void nonAdminGroupsClaimCreatesNoAdminRowAndStillSucceeds() {
        String sub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, sub, List.of("USER"));

        ResponseEntity<Map> response = callMe(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminRowCountFor(sub)).isZero();
    }

    @Test
    void groupsOrderDoesNotAffectAdminRowCreation() {
        String subOrderA = UUID.randomUUID().toString();
        String subOrderB = UUID.randomUUID().toString();
        String tokenA = LocalAuthTokens.mint(restTemplate, subOrderA, List.of("ADMIN", "USER"));
        String tokenB = LocalAuthTokens.mint(restTemplate, subOrderB, List.of("USER", "ADMIN"));

        callMe(tokenA);
        callMe(tokenB);

        assertThat(adminRowCountFor(subOrderA)).isEqualTo(1);
        assertThat(adminRowCountFor(subOrderB)).isEqualTo(1);
    }

    private ResponseEntity<Map> callMe(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private int adminRowCountFor(String sub) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin WHERE id = ?", Integer.class, sub);
        return count == null ? 0 : count;
    }

    private String adminIdFor(String sub) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return jdbcTemplate.queryForObject("SELECT id FROM admin WHERE id = ?", String.class, sub);
    }
}
