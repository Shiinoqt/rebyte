package it.rebyte.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@ActiveProfiles("local-auth")
@Testcontainers
class ProtectedRouteRateLimitIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @TestConfiguration
    static class StubTokenConfig {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            Jwt jwt = Jwt.withTokenValue("stub-token").header("alg", "none").subject("buyer")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
            return token -> Mono.just(jwt);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RouteDefinitionLocator routeDefinitions;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @Test
    void protectedRoutesKeepLimiterAndWebhookDoesNotTouchRedis() {
        Map<String, RouteDefinition> routes = routeDefinitions.getRouteDefinitions().collectList().block()
                .stream().collect(Collectors.toMap(RouteDefinition::getId, route -> route));
        for (String id : List.of("user-service", "catalog-service", "messaging-service", "order-service",
                "order-offer-create", "order-user-reviews", "user-username-available", "catalog-user-listings", "payment-service", "messaging-ws", "messaging-chat-write")) {
            assertThat(routes.get(id).getFilters()).extracting(filter -> filter.getName())
                    .contains("RequestRateLimiter");
        }
        assertThat(routes.get("payment-webhook").getFilters()).isEmpty();
        assertThat(routes.get("internal-deny").getFilters()).extracting(filter -> filter.getName())
                .doesNotContain("RequestRateLimiter");

        WebClient protectedClient = WebClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer stub-token").build();
        List<org.springframework.http.ResponseEntity<String>> responses = Flux.range(0, 150)
                .flatMap(index -> protectedClient.get().uri("/orders/example")
                        .exchangeToMono(response -> response.toEntity(String.class)), 32)
                .collectList().block(Duration.ofSeconds(60));
        assertThat(responses).isNotNull();
        assertThat(responses.stream().filter(response -> response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                .toList()).isNotEmpty().allSatisfy(response -> {
                    assertThat(response.getHeaders().getContentType()).isNotNull()
                            .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                                    .isTrue());
                    assertThat(response.getBody()).contains("\"status\":429");
                });

        long keysBeforeWebhook = redis.keys("*").count().block(Duration.ofSeconds(5));
        assertThat(keysBeforeWebhook).isPositive();
        WebClient publicClient = WebClient.builder().baseUrl("http://localhost:" + port).build();
        Flux.range(0, 5).concatMap(index -> publicClient.post().uri("/payments/webhook")
                .bodyValue("{}")
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode())))
                .collectList().block(Duration.ofSeconds(30));
        assertThat(redis.keys("*").count().block(Duration.ofSeconds(5))).isEqualTo(keysBeforeWebhook);
    }
}
