package it.rebyte.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
        properties = "eureka.client.enabled=false")
@ActiveProfiles("local-auth")
@Testcontainers
class OrderRoutesIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final List<String> ORDER_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> USER_CALLS = new CopyOnWriteArrayList<>();
    private static final HttpServer ORDER = startStub("order-service", ORDER_CALLS);
    private static final HttpServer USER = startStub("user-service", USER_CALLS);

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri",
                () -> "http://localhost:" + ORDER.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://localhost:" + USER.getAddress().getPort());
    }

    @TestConfiguration
    static class StubTokenConfig {
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

    @BeforeEach
    void clearCalls() {
        ORDER_CALLS.clear();
        USER_CALLS.clear();
    }

    @AfterAll
    static void stopStubs() {
        ORDER.stop(0);
        USER.stop(0);
    }

    @Test
    void reviewListOfAUserReachesOrderService() throws Exception {
        Reply reply = send("GET", "/users/seller-1/reviews", "Bearer member-a");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isEqualTo("order-service /users/seller-1/reviews");
        assertThat(ORDER_CALLS).containsExactly("GET /users/seller-1/reviews");
        assertThat(USER_CALLS).isEmpty();
    }

    @Test
    void otherUserPathsStillReachUserService() throws Exception {
        Reply reply = send("GET", "/users/seller-1", "Bearer member-a");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isEqualTo("user-service /users/seller-1");
        assertThat(USER_CALLS).containsExactly("GET /users/seller-1");
        assertThat(ORDER_CALLS).isEmpty();
    }

    @Test
    void anonymousReviewListReachesOrderService() throws Exception {
        Reply reply = send("GET", "/users/seller-1/reviews", null);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(ORDER_CALLS).containsExactly("GET /users/seller-1/reviews");
        assertThat(USER_CALLS).isEmpty();
    }

    @Test
    void licenceAdminProblemAndReviewPathsReachOrderService() throws Exception {
        assertThat(send("GET", "/licences/me", "Bearer member-a").status()).isEqualTo(200);
        assertThat(send("GET", "/admin/problems?status=OPEN", "Bearer member-a").status()).isEqualTo(200);
        assertThat(send("POST", "/admin/problems/p-1/dismiss", "Bearer member-a").status()).isEqualTo(200);
        assertThat(send("GET", "/reviews/r-1", "Bearer member-a").status()).isEqualTo(200);

        assertThat(ORDER_CALLS).containsExactly("GET /licences/me", "GET /admin/problems",
                "POST /admin/problems/p-1/dismiss", "GET /reviews/r-1");
        assertThat(USER_CALLS).isEmpty();
    }

    @Test
    void internalLicencePathStaysDenied() throws Exception {
        Reply reply = send("GET", "/internal/licences?buyerSub=x&listingId=y", "Bearer member-a");

        assertThat(reply.status()).isEqualTo(403);
        assertThat(ORDER_CALLS).isEmpty();
        assertThat(USER_CALLS).isEmpty();
    }

    @Test
    void orderServicePredicateListsBritishLicencePathOnly() {
        RouteDefinition route = routes().get("order-service");
        String americanSpelling = "/licen" + "ses";

        assertThat(route).isNotNull();
        assertThat(route.getPredicates()).flatExtracting(predicate -> predicate.getArgs().values())
                .contains("/licences/**", "/admin/problems/**")
                .noneMatch(value -> value.contains(americanSpelling));
    }

    @Test
    void reviewListRouteWinsOverUserServiceAndIsRateLimited() {
        RouteDefinition route = routes().get("order-user-reviews");

        assertThat(route).isNotNull();
        assertThat(route.getOrder()).isLessThan(0);
        assertThat(route.getPredicates()).extracting(PredicateDefinition::getName).contains("Method");
        assertThat(route.getFilters()).filteredOn(filter -> "RequestRateLimiter".equals(filter.getName()))
                .isNotEmpty()
                .allSatisfy(filter -> assertThat(filter.getArgs()).containsEntry("key-resolver",
                        "#{@ipKeyResolver}"));
    }

    private Map<String, RouteDefinition> routes() {
        return routeDefinitions.getRouteDefinitions().collectList().block().stream()
                .collect(Collectors.toMap(RouteDefinition::getId, route -> route));
    }

    private Reply send(String method, String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        request.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> response = HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private record Reply(int status, String body) {
    }

    private static HttpServer startStub(String name, List<String> calls) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                calls.add(exchange.getRequestMethod() + " " + path);
                byte[] body = (name + " " + path).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
