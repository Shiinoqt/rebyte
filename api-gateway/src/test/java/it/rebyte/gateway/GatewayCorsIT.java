package it.rebyte.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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

import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "rebyte.cors.allowed-origins=http://localhost:4200,https://rebyte.tech"})
@ActiveProfiles("local-auth")
@Testcontainers
class GatewayCorsIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final String ORIGIN = "http://localhost:4200";

    private static final List<String> USER_CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer USER = startStub(USER_CALLS);
    private static final List<String> ORDER_CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer ORDER = startStub(ORDER_CALLS);
    private static final List<String> MESSAGING_CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer MESSAGING = startStub(MESSAGING_CALLS);

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://localhost:" + USER.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri",
                () -> "http://localhost:" + ORDER.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.messaging-service[0].uri",
                () -> "http://localhost:" + MESSAGING.getAddress().getPort());
    }

    @BeforeEach
    void clearCalls() {
        USER_CALLS.clear();
        ORDER_CALLS.clear();
        MESSAGING_CALLS.clear();
    }

    @AfterAll
    static void stopStubs() {
        USER.stop(0);
        ORDER.stop(0);
        MESSAGING.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://localhost:4200", "https://rebyte.tech" })
    void allowedPreflightRunsBeforeAuthenticationAndDoesNotReachDownstream(String origin) throws Exception {
        HttpResponse<String> response = preflight("/users/me", origin, "PUT", "authorization,content-type");
        assertAllowedPreflight(response, "PUT", origin);
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase())
                .contains("authorization", "content-type");
        assertThat(USER_CALLS).isEmpty();
        assertThat(ORDER_CALLS).isEmpty();
        assertThat(MESSAGING_CALLS).isEmpty();
    }

    @Test
    void orderPreflightAcceptsIdempotencyKeyWithoutCallingDownstream() throws Exception {
        HttpResponse<String> response = preflight("/orders", ORIGIN, "POST", "idempotency-key");
        assertAllowedPreflight(response, "POST", ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase())
                .contains("idempotency-key");
        assertThat(ORDER_CALLS).isEmpty();
        assertThat(USER_CALLS).isEmpty();
    }

    @Test
    void disallowedPreflightHasNoAllowOriginHeader() throws Exception {
        HttpResponse<String> response = preflight("/users/me", "http://evil.example", "PUT", "authorization,content-type");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(USER_CALLS).isEmpty();
    }

    @Test
    void hostileOriginCannotRouteAnAuthenticatedRequest() throws Exception {
        HttpResponse<String> response = send("GET", "/users/me",
                "Origin", "https://rebyte.tech.attacker.example", "Authorization", "Bearer buyer");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(USER_CALLS).isEmpty();
    }

    @Test
    void gatewayUnauthorizedResponseCarriesCorsAndProblemJson() throws Exception {
        HttpResponse<String> response = send("GET", "/users/me", "Origin", ORIGIN);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(response.body()).contains("\"status\":401");
        assertThat(USER_CALLS).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://localhost:4200", "https://rebyte.tech" })
    void websocketPathsApplyTheSameOriginAllowlist(String origin) throws Exception {
        assertThat(send("GET", "/ws/info", "Origin", origin).statusCode()).isNotEqualTo(403);
        MESSAGING_CALLS.clear();
        HttpResponse<String> rejected = send("GET", "/ws/info", "Origin", "http://evil.example");
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(rejected.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(MESSAGING_CALLS).isEmpty();
    }

    @Test
    void actualReplayResponseExposesTheAuditIdToBrowserCode() throws Exception {
        String path = "/admin/orders/dead-letters/q1/replay";
        HttpResponse<String> response = send("POST", path, "Origin", ORIGIN, "Authorization", "Bearer admin");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Audit-Id")).contains("audit-1");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElse("").toLowerCase())
                .contains("x-audit-id");
        assertThat(ORDER_CALLS).containsExactly("POST " + path);
        assertThat(USER_CALLS).isEmpty();
    }

    private HttpResponse<String> preflight(String path, String origin, String method, String headers) throws Exception {
        return send("OPTIONS", path, "Origin", origin, "Access-Control-Request-Method", method,
                "Access-Control-Request-Headers", headers);
    }

    private void assertAllowedPreflight(HttpResponse<String> response, String method, String origin) {
        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains(method);
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials").orElse("")).isNotEqualTo("true");
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

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

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
