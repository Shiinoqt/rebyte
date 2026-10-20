package it.rebyte.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
class UnsubscribePermitIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final CopyOnWriteArrayList<String> SEEN = new CopyOnWriteArrayList<>();
    private static final HttpServer MESSAGING = startMessaging();

    @DynamicPropertySource
    static void messagingInstance(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.messaging-service[0].uri",
                () -> "http://localhost:" + MESSAGING.getAddress().getPort());
    }

    @TestConfiguration
    static class StubTokenConfig {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            Jwt jwt = Jwt.withTokenValue("stub-token").header("alg", "none").subject("admin-1")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
            return token -> Mono.just(jwt);
        }
    }

    @LocalServerPort
    private int port;

    @AfterAll
    static void stopMessaging() {
        MESSAGING.stop(0);
    }

    @Test
    void anonymousGetAndPostOnTheUnsubscribePathReachMessagingService() throws Exception {
        assertThat(request("GET", "/notifications/email-preferences/unsubscribe?token=t", null)).isEqualTo(200);
        assertThat(request("POST", "/notifications/email-preferences/unsubscribe?token=t", null)).isEqualTo(200);

        assertThat(SEEN).contains("GET /notifications/email-preferences/unsubscribe?token=t",
                "POST /notifications/email-preferences/unsubscribe?token=t");
    }

    @Test
    void everyOtherNotificationsPathStillNeedsAToken() throws Exception {
        assertThat(request("GET", "/notifications", null)).isEqualTo(401);
        assertThat(request("GET", "/notifications/email-preferences", null)).isEqualTo(401);
        assertThat(request("PUT", "/notifications/email-preferences/unsubscribe/other", null)).isEqualTo(401);
        assertThat(request("PUT", "/notifications/email-preferences/unsubscribe?token=t", null)).isEqualTo(401);
    }

    @Test
    void adminReplayNeedsATokenAndThenReachesMessagingService() throws Exception {
        String path = "/admin/messaging/dead-letters/x/replay";

        assertThat(request("POST", path, null)).isEqualTo(401);
        assertThat(request("POST", path, "Bearer stub-token")).isEqualTo(200);
        assertThat(SEEN).contains("POST " + path);
    }

    @Test
    void internalPathsStayDeniedForAnAuthenticatedCaller() throws Exception {
        assertThat(request("GET", "/internal/users/x/email", "Bearer stub-token")).isEqualTo(403);
    }

    private int request(String method, String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static HttpServer startMessaging() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String query = exchange.getRequestURI().getRawQuery();
                SEEN.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                        + (query == null ? "" : "?" + query));
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
