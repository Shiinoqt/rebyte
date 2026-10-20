package it.rebyte.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@ActiveProfiles("local-auth")
@Testcontainers
class OfferRateLimitIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @TestConfiguration
    static class StubTokenConfig {
        // Il sub coincide con il token grezzo: bearer diversi = utenti diversi,
        // senza dover firmare JWT reali per isolare le chiavi di subKeyResolver.
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(Jwt.withTokenValue(token).header("alg", "none").subject(token)
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build());
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RouteDefinitionLocator routeDefinitions;

    @Test
    void offerCreateRouteExistsAndOfferPathsRouteToOrderService() {
        Map<String, RouteDefinition> routes = routeDefinitions.getRouteDefinitions().collectList().block()
                .stream().collect(Collectors.toMap(RouteDefinition::getId, route -> route));

        RouteDefinition offerCreate = routes.get("order-offer-create");
        assertThat(offerCreate).isNotNull();
        assertThat(offerCreate.getOrder()).isLessThan(0);
        assertThat(offerCreate.getPredicates()).extracting(PredicateDefinition::getName).contains("Method");
        assertThat(offerCreate.getFilters()).filteredOn(filter -> "RequestRateLimiter".equals(filter.getName()))
                .isNotEmpty()
                .allSatisfy(filter -> assertThat(filter.getArgs()).containsEntry("key-resolver",
                        "#{@subKeyResolver}"));

        RouteDefinition orderService = routes.get("order-service");
        assertThat(orderService).isNotNull();
        assertThat(orderService.getPredicates()).flatExtracting(predicate -> predicate.getArgs().values())
                .contains("/offers/**");
    }

    @Test
    void postOffersBurstFromOneUserIsThrottled() {
        WebClient client = WebClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer burst-buyer").build();

        List<ResponseEntity<String>> responses = List.copyOf(IntStream.range(0, 15)
                .mapToObj(index -> client.post().uri("/offers").contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}").exchangeToMono(response -> response.toEntity(String.class))
                        .block(Duration.ofSeconds(10)))
                .toList());

        List<ResponseEntity<String>> throttled = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS).toList();
        assertThat(throttled).isNotEmpty();
        throttled.forEach(response -> {
            assertThat(response.getHeaders().getContentType()).isNotNull()
                    .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                            .isTrue());
            assertThat(response.getBody()).contains("\"status\":429");
        });
    }

    @Test
    void perUserLimitIsolatesUsersAndDoesNotAffectGetOffers() {
        WebClient buyerA = WebClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer isolation-buyer-a").build();

        boolean throttled = false;
        for (int attempt = 0; attempt < 20 && !throttled; attempt++) {
            HttpStatusCode status = buyerA.post().uri("/offers").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode()))
                    .block(Duration.ofSeconds(10));
            throttled = status == HttpStatus.TOO_MANY_REQUESTS;
        }
        assertThat(throttled).isTrue();

        WebClient buyerB = WebClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer isolation-buyer-b").build();
        HttpStatusCode buyerBStatus = buyerB.post().uri("/offers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode()))
                .block(Duration.ofSeconds(10));
        assertThat(buyerBStatus).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        HttpStatusCode buyerAGetStatus = buyerA.get().uri("/offers/some-id")
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode()))
                .block(Duration.ofSeconds(10));
        assertThat(buyerAGetStatus).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void anonymousPostOffersRequiresAuthentication() {
        WebClient client = WebClient.builder().baseUrl("http://localhost:" + port).build();
        HttpStatusCode status = client.post().uri("/offers").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode()))
                .block(Duration.ofSeconds(10));
        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
