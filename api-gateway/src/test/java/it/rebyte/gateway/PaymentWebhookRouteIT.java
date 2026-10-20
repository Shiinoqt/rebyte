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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Headers;

import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@ActiveProfiles("local-auth")
class PaymentWebhookRouteIT {

    private static final byte[] RAW_BODY = "{ \"id\" : \"WH-1\", \"event_type\":\"PAYMENT.CAPTURE.COMPLETED\" }"
            .getBytes(StandardCharsets.UTF_8);
    private static final AtomicReference<byte[]> RECEIVED = new AtomicReference<>();
    private static final AtomicReference<Headers> HEADERS = new AtomicReference<>();
    private static final HttpServer PAYMENT = startPayment();

    @DynamicPropertySource
    static void paymentInstance(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.payment-service[0].uri",
                () -> "http://localhost:" + PAYMENT.getAddress().getPort());
    }

    @TestConfiguration
    static class StubTokenConfig {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            Jwt jwt = Jwt.withTokenValue("stub-token").header("alg", "none").subject("operator")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
            return token -> Mono.just(jwt);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RouteLocator routes;

    @AfterAll
    static void stopPayment() {
        PAYMENT.stop(0);
    }

    @Test
    void anonymousPostForwardsExactBodyAndPayPalHeaders() throws Exception {
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(url("/payments/webhook"))
                .header("Content-Type", "application/json")
                .header("PayPal-Transmission-Id", "transmission-1")
                .header("PayPal-Transmission-Sig", "signed-looking-data")
                .POST(HttpRequest.BodyPublishers.ofByteArray(RAW_BODY)).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(RECEIVED.get()).containsExactly(RAW_BODY);
        assertThat(HEADERS.get().getFirst("PayPal-Transmission-Id")).isEqualTo("transmission-1");
        assertThat(HEADERS.get().getFirst("PayPal-Transmission-Sig")).isEqualTo("signed-looking-data");
        assertThat(routes.getRoutes().map(route -> route.getId()).collectList().block())
                .containsSubsequence("internal-deny", "payment-webhook", "payment-service");
    }

    @Test
    void onlyPostWebhookIsPublicAndInternalRemainsDenied() throws Exception {
        assertThat(request("GET", "/payments/webhook", null)).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(request("POST", "/payments/example/capture", null)).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(request("GET", "/admin/orders/stuck", null)).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(request("GET", "/internal/payments/example/status", "Bearer stub-token"))
                .isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    private int request(String method, String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url(path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpServer startPayment() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/payments/webhook", exchange -> {
                RECEIVED.set(exchange.getRequestBody().readAllBytes());
                HEADERS.set(exchange.getRequestHeaders());
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
