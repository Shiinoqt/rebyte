package it.rebyte.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@ActiveProfiles("local-auth")
@Testcontainers
class ChatRateLimitIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final HttpServer MESSAGING = startMessaging();

    @DynamicPropertySource
    static void messagingInstance(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.messaging-service[0].uri",
                () -> "http://localhost:" + MESSAGING.getAddress().getPort());
    }

    @TestConfiguration
    static class StubTokenConfig {
        // Il sub coincide con il token grezzo: bearer diversi = utenti diversi.
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(Jwt.withTokenValue(token).header("alg", "none").subject(token)
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build());
        }
    }

    @LocalServerPort
    private int port;

    @AfterAll
    static void stopMessaging() {
        MESSAGING.stop(0);
    }

    @Test
    void conversationBurstIsThrottledWithProblemJsonAndIsolatedFromOtherUsersAndReads() {
        WebClient alice = client("alice");

        ResponseEntity<String> throttled = null;
        for (int attempt = 0; attempt < 20 && throttled == null; attempt++) {
            ResponseEntity<String> response = send(alice, HttpMethod.POST, "/conversations");
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throttled = response;
            }
        }
        assertThat(throttled).isNotNull();
        assertThat(throttled.getHeaders().getContentType()).isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        assertThat(throttled.getBody()).contains("\"status\":429");

        assertThat(send(client("bob"), HttpMethod.POST, "/conversations").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(send(alice, HttpMethod.GET, "/conversations").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(alice, HttpMethod.POST, "/conversations/c1/messages").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(send(alice, HttpMethod.POST, "/messages/image-upload-policy").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void messageSendsShareTheConversationBucketWithinOneUser() {
        WebClient carol = client("carol");

        HttpStatusCode last = HttpStatus.CREATED;
        for (int attempt = 0; attempt < 20 && last != HttpStatus.TOO_MANY_REQUESTS; attempt++) {
            last = send(carol, HttpMethod.POST, "/conversations/c1/messages").getStatusCode();
        }
        assertThat(last).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(send(carol, HttpMethod.POST, "/conversations").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void uploadPolicyBurstIsThrottledPerUserAndSharesTheChatWriteBucket() {
        WebClient uploader = client("upload-burst-user");

        assertThat(send(uploader, HttpMethod.POST, "/messages/image-upload-policy").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> throttled = null;
        for (int attempt = 0; attempt < 20 && throttled == null; attempt++) {
            ResponseEntity<String> response = send(uploader, HttpMethod.POST, "/messages/image-upload-policy");
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throttled = response;
            }
        }
        assertThat(throttled).isNotNull();
        assertThat(throttled.getHeaders().getContentType()).isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue());
        assertThat(throttled.getBody()).contains("\"status\":429");
        assertThat(send(uploader, HttpMethod.POST, "/conversations").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(send(client("other-upload-user"), HttpMethod.POST, "/messages/image-upload-policy").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(send(uploader, HttpMethod.GET, "/conversations").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private WebClient client(String token) {
        return WebClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token).build();
    }

    private static ResponseEntity<String> send(WebClient client, HttpMethod method, String path) {
        return client.method(method).uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchangeToMono(response -> response.toEntity(String.class)).block(Duration.ofSeconds(10));
    }

    private static HttpServer startMessaging() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/conversations", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders("POST".equals(exchange.getRequestMethod()) ? 201 : 200, -1);
                exchange.close();
            });
            server.createContext("/messages/image-upload-policy", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(201, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
