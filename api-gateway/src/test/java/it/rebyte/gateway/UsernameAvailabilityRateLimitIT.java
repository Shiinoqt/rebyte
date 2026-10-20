package it.rebyte.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "rebyte.cors.allowed-origins=http://localhost:4200"})
@ActiveProfiles("local-auth")
@Testcontainers
class UsernameAvailabilityRateLimitIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final List<String> USER_CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer USER = startStub(USER_CALLS);

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://localhost:" + USER.getAddress().getPort());
    }

    @BeforeEach
    void clearCalls() {
        USER_CALLS.clear();
    }

    @AfterAll
    static void stopStubs() {
        USER.stop(0);
    }

    @Test
    void availabilityBurstIsLimitedWithReadableCorsErrorsAndAnIndependentProfileBucket() {
        WebClient client =
                WebClient.builder()
                        .baseUrl("http://localhost:" + port).defaultHeader("Origin", "http://localhost:4200").build();
        List<ResponseEntity<String>> responses = Flux.range(0, 20)
                .flatMap(index -> client.get().uri("/users/username-available?u=abc")
                        .exchangeToMono(response -> response.toEntity(String.class)), 20)
                .collectList().block(Duration.ofSeconds(30));
        assertThat(responses).isNotNull();
        List<ResponseEntity<String>> throttled = responses.stream()
                .filter(response -> response.getStatusCode().value() == 429).toList();
        assertThat(throttled).isNotEmpty().allSatisfy(response -> {
            assertThat(response.getHeaders().getContentType()).isNotNull()
                    .satisfies(type -> assertThat(type.isCompatibleWith(
                            MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
            assertThat(response.getBody()).contains("https://rebyte.it/problems/rate-limited", "\"status\":429");
            assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("http://localhost:4200");
        });
        assertThat(responses).anySatisfy(response -> assertThat(response.getStatusCode().value()).isEqualTo(200));
        ResponseEntity<String> profile = client.get().uri("/users/s1")
                .exchangeToMono(response -> response.toEntity(String.class)).block(Duration.ofSeconds(10));
        assertThat(profile).isNotNull();
        assertThat(profile.getStatusCode().value()).isEqualTo(200);
        assertThat(USER_CALLS).contains("GET /users/username-available", "GET /users/s1");
    }

    @TestConfiguration
    static class StubTokenConfig {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> {
                if ("malformed".equals(token)) {
                    return Mono.error(new BadJwtException("Invalid test token"));
                }
                return Mono.just(Jwt.withTokenValue(token).header("alg", "none").subject(token)
                        .claim("cognito:groups", List.of("ADMIN"))
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build());
            };
        }
    }

    @LocalServerPort
    private int port;

    private static HttpServer startStub(List<String> calls) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                exchange.getResponseHeaders().add("X-Audit-Id", "audit-1");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
