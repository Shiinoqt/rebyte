package it.rebyte.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * D-32/IDENT-02: 401 e 403 dal gateway portano un corpo RFC 9457 parseable,
 * non piu una risposta vuota — i due errori che un client Angular gestisce
 * piu spesso.
 *
 * Il caso 403 usa un chiamante con token valido (mockJwt) perche
 * SecurityConfig lascia che /internal/** ricada su
 * anyExchange().authenticated(): il deny reale arriva dopo, dalla rotta del
 * gateway (D-56) — e la risposta che questo test verifica avere comunque un
 * corpo problem+json, non solo lo stato.
 *
 * Il caso 429 vive in RateLimitProblemJsonIT, non qui, e non per comodita: il
 * difetto D-32 era un artefatto del commit della risposta, e un client
 * mock-bound come questo non riproduce quella semantica. Un'asserzione sul 429
 * aggiunta qui passerebbe con il bug dentro, cioe sarebbe peggio che non
 * averla. Serve un server vero e un bucket Redis vero: quello fa l'IT.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
class ProblemDetailErrorBodyTest {

    @Autowired
    private ApplicationContext context;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Test
    void missingAuthorizationHeaderReturns401WithProblemJsonBody() {
        webTestClient.get()
                .uri("/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").exists()
                .jsonPath("$.title").exists()
                .jsonPath("$.status").isEqualTo(401);
    }

    /**
     * The 401 a real client actually hits most: an EXPIRED or tampered token,
     * not a missing header. {@code problemJsonAuthenticationEntryPoint}'s own
     * javadoc claims to cover "nessun token, o token non valido", but
     * {@code oauth2ResourceServer} installs its own
     * {@code BearerTokenServerAuthenticationEntryPoint}, which takes
     * precedence for token failures — so before this was pinned, a bad token
     * returned a bodyless 401 with only a {@code WWW-Authenticate} header
     * while a missing one returned problem+json. An Angular client cannot
     * parse two different shapes for the same status.
     *
     * <p>A syntactically invalid token fails in the decoder before any JWKS
     * fetch, so this test needs no issuer reachable.
     */
    @Test
    void invalidTokenReturns401WithProblemJsonBody() {
        webTestClient.get()
                .uri("/users/me")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").exists()
                .jsonPath("$.title").exists()
                .jsonPath("$.status").isEqualTo(401);
    }

    /**
     * Replacing the resource server's own entry point must not cost the
     * RFC 6750 challenge header: a 401 from a bearer-protected resource
     * SHOULD carry {@code WWW-Authenticate}, and monitoring and proxies key
     * on the {@code error} code. {@code error_description} is deliberately
     * NOT reinstated — Spring's default leaked decoder internals there
     * ("Invalid JWT serialization: Missing dot delimiter(s)"), and
     * T-02-04 limits error responses to type, title and status.
     */
    @Test
    void invalidTokenAlsoCarriesTheRfc6750Challenge() {
        webTestClient.get()
                .uri("/users/me")
                .header("Authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value("WWW-Authenticate", challenge -> {
                    org.assertj.core.api.Assertions.assertThat(challenge)
                            .isEqualTo("Bearer error=\"invalid_token\"");
                });
    }

    @Test
    void aMissingTokenCarriesABareChallengeWithNoErrorCode() {
        // RFC 6750 section 3: a request lacking authentication information
        // altogether SHOULD NOT be answered with an error code — there was no
        // failed attempt to report.
        webTestClient.get()
                .uri("/users/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer");
    }

    @Test
    void internalPathReturns403WithProblemJsonBodyForAnAuthenticatedCaller() {
        // The body assertion is the point this test's own name and the class
        // javadoc both claim, and it was previously left unasserted: only the
        // status was checked, so a regression to a bodyless 403 would have
        // passed.
        webTestClient
                .mutateWith(mockJwt())
                .get()
                .uri("/internal/users/some-sub")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").exists()
                .jsonPath("$.title").exists()
                .jsonPath("$.status").isEqualTo(403);
    }
}
