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
        properties = {"eureka.client.enabled=false", "rebyte.cors.allowed-origins=http://localhost:4200"})
@ActiveProfiles("local-auth")
@Testcontainers
class AnonymousReadsIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final List<String> USER_CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer USER = startStub(USER_CALLS);
    private static final List<String> CATALOG_CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer CATALOG = startStub(CATALOG_CALLS);
    private static final List<String> ORDER_CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer ORDER = startStub(ORDER_CALLS);

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://localhost:" + USER.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.catalog-service[0].uri",
                () -> "http://localhost:" + CATALOG.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri",
                () -> "http://localhost:" + ORDER.getAddress().getPort());
    }

    @BeforeEach
    void clearCalls() {
        USER_CALLS.clear();
        CATALOG_CALLS.clear();
        ORDER_CALLS.clear();
    }

    @AfterAll
    static void stopStubs() {
        USER.stop(0);
        CATALOG.stop(0);
        ORDER.stop(0);
    }

    @Test
    void anonymousPublicCatalogReadsReachCatalog() throws Exception {
        for (String path : List.of("/search?q=x", "/search/nested", "/categories", "/categories/c1",
                "/categories/c1/schema", "/categories/c1/listings", "/listings/l1")) {
            assertThat(send("GET", path).statusCode()).as(path).isEqualTo(200);
        }
        assertThat(CATALOG_CALLS).containsExactly("GET /search", "GET /search/nested", "GET /categories",
                "GET /categories/c1", "GET /categories/c1/schema", "GET /categories/c1/listings", "GET /listings/l1");
        assertThat(USER_CALLS).isEmpty();
        assertThat(ORDER_CALLS).isEmpty();
    }

    @Test
    void anonymousPublicUserReadsReachTheirOwningService() throws Exception {
        assertThat(send("GET", "/users/s1").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/users/username-available?u=abc").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/users/s1/reviews").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/users/s1/listings").statusCode()).isEqualTo(200);
        assertThat(USER_CALLS).containsExactly("GET /users/s1", "GET /users/username-available");
        assertThat(ORDER_CALLS).containsExactly("GET /users/s1/reviews");
        assertThat(CATALOG_CALLS).containsExactly("GET /users/s1/listings");
    }

    @Test
    void ownerReadsAndWritesStayProtectedWithoutDownstreamCalls() throws Exception {
        for (String path : List.of("/users/me", "/users/me/avatar", "/users/me/listings", "/listings/mine",
                "/favourites", "/listings/l1/versions", "/orders/me/purchases")) {
            assertThat(send("GET", path).statusCode()).as(path).isEqualTo(401);
        }
        assertThat(send("POST", "/listings/hardware").statusCode()).isEqualTo(401);
        assertThat(send("POST", "/categories").statusCode()).isEqualTo(401);
        assertThat(send("PUT", "/users/s1/follow").statusCode()).isEqualTo(401);
        assertThat(USER_CALLS).isEmpty();
        assertThat(CATALOG_CALLS).isEmpty();
        assertThat(ORDER_CALLS).isEmpty();
    }

    @Test
    void internalPathsStayDeniedForAuthenticatedCallers() throws Exception {
        assertThat(send("GET", "/internal/users/x", "Authorization", "Bearer member").statusCode()).isEqualTo(403);
        assertThat(USER_CALLS).isEmpty();
        assertThat(CATALOG_CALLS).isEmpty();
        assertThat(ORDER_CALLS).isEmpty();
    }

    @Test
    void presentedMalformedBearerIsRejectedEvenOnPublicReads() throws Exception {
        assertThat(send("GET", "/search", "Authorization", "Bearer malformed").statusCode()).isEqualTo(401);
        assertThat(CATALOG_CALLS).isEmpty();
    }

    @Test
    void adminSearchRequiresTokenAndThenReachesCatalog() throws Exception {
        assertThat(send("GET", "/admin/search/rebuild").statusCode()).isEqualTo(401);
        assertThat(CATALOG_CALLS).isEmpty();
        assertThat(send("GET", "/admin/search/rebuild", "Authorization", "Bearer admin").statusCode()).isEqualTo(200);
        assertThat(CATALOG_CALLS).containsExactly("GET /admin/search/rebuild");
        assertThat(USER_CALLS).isEmpty();
        assertThat(ORDER_CALLS).isEmpty();
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
