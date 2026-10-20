package it.rebyte.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-32, la meta che mancava: un 429 dal rate limiter porta un corpo
 * {@code application/problem+json} VERO, non un header che lo promette e un
 * corpo lungo zero.
 *
 * <p><b>Perche un server Netty reale e non {@code bindToApplicationContext}.</b>
 * Il difetto che questo test chiude e un artefatto del <i>commit</i> della
 * risposta: il gancio {@code beforeCommit} precedente riusciva a cambiare gli
 * header e perdeva la scrittura del corpo perche la risposta stava gia
 * committando. Uno scambio mock-bound non riproduce quella semantica, quindi un
 * verde ottenuto li sarebbe stato verde anche con il bug dentro. Questa fase ha
 * gia prodotto tre volte "evidenza" che non misurava cio che dichiarava: qui si
 * committa davvero, su una porta vera, contro un Redis vero.
 *
 * <p><b>Perche il bucket non viene rimpicciolito.</b> Sarebbe piu rapido
 * sovrascrivere {@code burstCapacity} dalle property del test, ma il binder di
 * Spring sostituisce una lista intera dalla sorgente a priorita piu alta: farlo
 * ridichiarerebbe {@code default-filters[0]} e con esso {@code throw-on-limit},
 * cioe proprio la riga che questo test esiste per difendere. Cancellata da
 * {@code application.yml}, il test resterebbe verde. Si paga quindi il burst di
 * produzione e si esaurisce quello reale.
 *
 * <p>Le richieste ammesse finiscono su {@code lb://user-service}, che qui non
 * gira, e tornano 503: atteso e irrilevante, le asserzioni guardano solo i 429.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@ActiveProfiles("local-auth")
@Testcontainers
class RateLimitProblemJsonIT {

    /**
     * {@code RedisContainerConnectionDetailsFactory} (spring-boot-data-redis,
     * gia sul classpath del gateway via starter-data-redis-reactive) riconosce
     * un {@code GenericContainer} dal nome dell'immagine: i tipi container di
     * {@code com.redis} non servono. Immagine allineata a
     * {@code infra/docker-compose.yml}.
     */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Il rate limiter sta DIETRO {@code anyExchange().authenticated()}: senza
     * un token la richiesta prende 401 e non lo raggiunge mai. Un decoder
     * stub fa arretrare l'autoconfigurazione del resource server
     * ({@code @ConditionalOnMissingBean(ReactiveJwtDecoder.class)}), cosi non
     * serve nessun issuer raggiungibile.
     */
    @TestConfiguration
    static class StubTokenConfig {

        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            Jwt jwt = Jwt.withTokenValue("stub-token")
                    .header("alg", "none")
                    .subject("rate-limit-it")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
            return token -> Mono.just(jwt);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void everyRateLimited429CarriesAParseableProblemJsonBody() throws Exception {
        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer stub-token")
                .build();

        // Burst di produzione: capacita 40, ricarica 20/s. 150 richieste in
        // parallelo superano il bucket reale come nella misura a mano del piano
        // 01-02 (60 passate, 90 negate).
        List<ResponseEntity<String>> responses = Flux.range(0, 150)
                .flatMap(i -> client.get()
                        .uri("/users/me")
                        .retrieve()
                        .toEntity(String.class)
                        .onErrorResume(org.springframework.web.reactive.function.client.WebClientResponseException.class,
                                ex -> Mono.just(ResponseEntity.status(ex.getStatusCode())
                                        .headers(ex.getHeaders())
                                        .body(ex.getResponseBodyAsString()))),
                        32)
                .collectList()
                .block(Duration.ofSeconds(60));

        assertThat(responses).isNotNull();

        List<ResponseEntity<String>> rateLimited = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                .toList();

        // Un test che non fa mai scattare il limite non dimostra nulla: e un
        // fallimento, non uno skip.
        assertThat(rateLimited)
                .as("il burst non ha esaurito il bucket — il test non ha misurato nulla")
                .isNotEmpty();

        for (ResponseEntity<String> response : rateLimited) {
            assertThat(response.getHeaders().getContentType())
                    .as("Content-Type del 429")
                    .isNotNull()
                    .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                            .as("il 429 deve dichiarare problem+json")
                            .isTrue());

            // Il cuore di D-32: prima di questa correzione il corpo era lungo
            // zero mentre l'header sopra prometteva problem+json.
            assertThat(response.getBody())
                    .as("corpo del 429 — vuoto significa che il difetto D-32 e tornato")
                    .isNotNull()
                    .isNotBlank();

            JsonNode body = objectMapper.readTree(response.getBody());
            assertThat(body.hasNonNull("type")).as("type").isTrue();
            assertThat(body.hasNonNull("title")).as("title").isTrue();
            assertThat(body.path("status").asInt()).as("status").isEqualTo(429);

            // Gli header del rate limiter erano l'unica meta informativa che
            // gia funzionava: la correzione non deve averla barattata col corpo.
            assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining"))
                    .as("X-RateLimit-Remaining deve sopravvivere alla via d'errore")
                    .isNotNull();
        }
    }
}
