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
 * Prova end-to-end di USER-01/D-05: mint -> {@code GET /users/me} -> {@code id}
 * byte-identico al {@code sub} -> riga JIT-provisionata -> idempotenza ->
 * 401 senza header -> concorrenza sullo stesso {@code sub}.
 *
 * <p>{@code webEnvironment = DEFINED_PORT} invece di {@code RANDOM_PORT}: il
 * minter local-auth firma i propri token con {@code iss} uguale a
 * {@code issuer-uri}, e {@code NimbusJwtDecoder.withIssuerLocation} risolve
 * la JWKS richiamando quello stesso {@code iss} via HTTP
 * ({@code /.well-known/openid-configuration}). Con una porta scelta da
 * Spring solo dopo l'avvio (RANDOM_PORT) l'{@code issuer-uri} configurato
 * punterebbe alla porta di sviluppo di default (8081), non a quella
 * effettiva del test, e la richiesta di discovery fallirebbe con connection
 * refused. Si riserva quindi una porta libera PRIMA dell'avvio del contesto
 * e si punta sia {@code server.port} che {@code issuer-uri} su di essa.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class UserJitProvisioningIT extends MySqlTestBase {

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
    void freshSubIsProvisionedAndReturnedVerbatimAndIdempotently() {
        String sub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, sub, List.of());

        ResponseEntity<Map> first = callMe(token);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().get("id")).isEqualTo(sub);
        String expectedHandle = "user_" + sub.substring(0, 8).toLowerCase();
        assertThat(first.getBody().get("username")).isEqualTo(expectedHandle);
        assertThat(rowCountFor(sub)).isEqualTo(1);

        ResponseEntity<Map> second = callMe(token);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(rowCountFor(sub)).isEqualTo(1);
    }

    @Test
    void missingAuthorizationHeaderIsRejectedAndCreatesNoRow() {
        String sub = UUID.randomUUID().toString();

        ResponseEntity<String> response = restTemplate.getForEntity("/users/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rowCountFor(sub)).isZero();
    }

    @Test
    void concurrentFirstRequestsForSameSubLeaveExactlyOneRow() {
        String sub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, sub, List.of());

        CompletableFuture<ResponseEntity<Map>> requestA =
                CompletableFuture.supplyAsync(() -> callMe(token));
        CompletableFuture<ResponseEntity<Map>> requestB =
                CompletableFuture.supplyAsync(() -> callMe(token));
        CompletableFuture.allOf(requestA, requestB).join();

        assertThat(requestA.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(requestB.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowCountFor(sub)).isEqualTo(1);
    }

    private ResponseEntity<Map> callMe(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private int rowCountFor(String sub) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE id = ?", Integer.class, sub);
        return count == null ? 0 : count;
    }
}
