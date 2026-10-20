package it.rebyte.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "eureka.client.enabled=false", "rebyte.cors.allowed-origins=http://localhost:4200,https://rebyte.tech" })
@ActiveProfiles("local-auth")
@Testcontainers
class MessagingWsRouteIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final AtomicReference<String> UPGRADE_AUTHORIZATION = new AtomicReference<>("unset");
    private static final AtomicInteger UPGRADE_COUNT = new AtomicInteger();
    private static final DisposableServer MESSAGING = startMessaging();

    @DynamicPropertySource
    static void messagingInstance(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.messaging-service[0].uri",
                () -> "http://localhost:" + MESSAGING.port());
    }

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
    private RouteLocator routes;

    @AfterAll
    static void stopMessaging() {
        MESSAGING.disposeNow();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "http://localhost:4200", "https://rebyte.tech" })
    void webSocketUpgradeReachesMessagingServiceWithoutAuthorizationHeader(String origin) {
        AtomicReference<String> echoed = new AtomicReference<>();

        HttpHeaders headers = new HttpHeaders();
        if (!origin.isEmpty()) headers.setOrigin(origin);
        new ReactorNettyWebSocketClient().execute(URI.create("ws://localhost:" + port + "/ws"), headers,
                session -> session.send(Mono.just(session.textMessage("ping")))
                        .thenMany(session.receive().take(1).map(WebSocketMessage::getPayloadAsText))
                        .doOnNext(echoed::set)
                        .then())
                .block(Duration.ofSeconds(5));

        assertThat(echoed.get()).isEqualTo("echo:ping");
        assertThat(UPGRADE_AUTHORIZATION.get()).isNull();
    }

    @Test
    void hostileOriginCannotUpgradeOrReachMessaging() {
        int before = UPGRADE_COUNT.get();
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://hostile.example");
        assertThatThrownBy(() -> new ReactorNettyWebSocketClient()
                .execute(URI.create("ws://localhost:" + port + "/ws"), headers, session -> Mono.empty())
                .block(Duration.ofSeconds(5))).hasMessageContaining("403");
        assertThat(UPGRADE_COUNT.get()).isEqualTo(before);
    }

    @Test
    void messagingWsRouteFollowsInternalDeny() {
        assertThat(routes.getRoutes().map(route -> route.getId()).collectList().block())
                .containsSubsequence("internal-deny", "messaging-ws");
    }

    private static DisposableServer startMessaging() {
        return HttpServer.create().port(0)
                .route(routes -> routes.ws("/ws", (inbound, outbound) -> {
                    UPGRADE_COUNT.incrementAndGet();
                    UPGRADE_AUTHORIZATION.set(inbound.headers().get("Authorization"));
                    return outbound.sendString(inbound.receive().asString().map(frame -> "echo:" + frame));
                }))
                .bindNow();
    }
}
