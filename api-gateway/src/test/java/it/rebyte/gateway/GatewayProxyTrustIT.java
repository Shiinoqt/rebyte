package it.rebyte.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.WebFilter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sun.net.httpserver.HttpServer;

import it.rebyte.gateway.config.RateLimiterConfig;

import reactor.core.publisher.Mono;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false", "server.forward-headers-strategy=FRAMEWORK",
        "GATEWAY_TRUSTED_PROXIES=^127[.]0[.]0[.]1$", "rebyte.cors.allowed-origins=https://rebyte.tech",
        "management.health.mongodb.enabled=false", "management.health.rabbit.enabled=false"
})
@ActiveProfiles({ "local-auth", "demo" })
class GatewayProxyTrustIT {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final HttpServer USER = startStub();

    @DynamicPropertySource
    static void userInstance(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://127.0.0.1:" + USER.getAddress().getPort());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @AfterAll
    static void stopStub() {
        USER.stop(0);
    }

    @Test
    void trustedProxySettingBindsToGatewayWebfluxNamespace() {
        assertThat(environment.getRequiredProperty("spring.cloud.gateway.server.webflux.trusted-proxies"))
                .isEqualTo("^127[.]0[.]0[.]1$");
    }

    @Test
    void trustedPeerUsesSanitizedHttpsMetadataAndClientIp() throws Exception {
        HttpResponse<String> response = send("127.0.0.1", "198.51.100.10",
                "Forwarded", "for=203.0.113.66;proto=http;host=forged.invalid",
                "X-Forwarded-Prefix", "/forged-prefix", "X-Forwarded-Ssl", "off");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Probe-Rate-Key")).contains("198.51.100.10");
        assertThat(response.headers().firstValue("X-Probe-Scheme")).contains("https");
        assertThat(response.headers().firstValue("X-Probe-Authority")).contains("api.rebyte.tech");
        assertThat(response.body()).doesNotContain("forged.invalid", "203.0.113.66", "/forged-prefix");
    }

    @Test
    void separateVisitorsHaveSeparateActualRateKeys() throws Exception {
        var first = send("127.0.0.1", "198.51.100.11");
        var second = send("127.0.0.1", "198.51.100.12");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("X-Probe-Rate-Key")).contains("198.51.100.11");
        assertThat(second.headers().firstValue("X-Probe-Rate-Key")).contains("198.51.100.12");
    }

    @Test
    void untrustedPeerCannotChooseItsRateKeySchemeOrHost() throws Exception {
        var response = send("127.0.0.2", "198.51.100.99",
                "Forwarded", "for=203.0.113.66;proto=https;host=forged.invalid");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Probe-Rate-Key")).contains("127.0.0.2");
        assertThat(response.headers().firstValue("X-Probe-Scheme")).contains("http");
        assertThat(response.headers().firstValue("X-Probe-Authority")).contains("127.0.0.1:" + port);
        assertThat(response.body()).doesNotContain("forged.invalid", "198.51.100.99", "203.0.113.66");
    }

    @ParameterizedTest
    @ValueSource(strings = { "/internal/users/buyer", "/actuator/gateway/routes", "/actuator/env" })
    void forwardingHeadersCannotExposePrivatePaths(String path) throws Exception {
        for (String source : new String[] { "127.0.0.1", "127.0.0.2" }) {
            var response = sendTo(source, "198.51.100.99", path, "valid",
                    "Forwarded", "for=203.0.113.66;proto=https;host=forged.invalid",
                    "X-Forwarded-Prefix", "/actuator/health");
            assertThat(response.statusCode()).isIn(403, 404);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "malformed" })
    void forwardingHeadersCannotBypassAuthentication(String token) throws Exception {
        for (String source : new String[] { "127.0.0.1", "127.0.0.2" }) {
            var response = sendTo(source, "198.51.100.99", "/users/me", token,
                    "Origin", "https://rebyte.tech",
                    "Forwarded", "for=203.0.113.66;proto=https;host=forged.invalid");
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("https://rebyte.tech");
        }
    }

    @Test
    void anonymousHealthRemainsAvailable() throws Exception {
        assertThat(sendTo("127.0.0.2", "198.51.100.99", "/actuator/health", "").statusCode())
                .isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = { "NONE", "NATIVE" })
    void demoRefusesAnUnsafeForwardingStrategy(String strategy) {
        new ApplicationContextRunner().withUserConfiguration(RateLimiterConfig.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"))
                .withPropertyValues("spring.cloud.gateway.server.webflux.trusted-proxies=^127[.]0[.]0[.]1$",
                        "server.forward-headers-strategy=" + strategy)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasRootCauseMessage("server.forward-headers-strategy"));
    }

    @Test
    void demoRefusesMissingProxyTrust() {
        new ApplicationContextRunner().withUserConfiguration(RateLimiterConfig.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"))
                .withPropertyValues("server.forward-headers-strategy=FRAMEWORK")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("spring.cloud.gateway.server.webflux.trusted-proxies");
                });
    }

    @Test
    void localProfileKeepsItsOriginalForwardingBehavior() {
        new ApplicationContextRunner().withUserConfiguration(RateLimiterConfig.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local-auth"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.containsBean("forwardedHeaderTransformer")).isFalse();
                });
    }

    private HttpResponse<String> send(String source, String clientIp, String... extraHeaders) throws Exception {
        return sendTo(source, clientIp, "/users/me", "valid", extraHeaders);
    }

    private HttpResponse<String> sendTo(String source, String clientIp, String path, String token,
            String... extraHeaders) throws Exception {
        HttpClient client = HttpClient.newBuilder().localAddress(InetAddress.getByName(source))
                .connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Forwarded-For", clientIp).header("X-Forwarded-Host", "api.rebyte.tech")
                .header("X-Forwarded-Proto", "https").header("X-Forwarded-Port", "443");
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        if (extraHeaders.length > 0) request.headers(extraHeaders);
        try (client) {
            return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> "valid".equals(token)
                    ? Mono.just(Jwt.withTokenValue(token).header("alg", "none").subject("buyer")
                            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build())
                    : Mono.error(new BadJwtException("Invalid test token"));
        }

        @Bean
        WebFilter observeActualRateResolver(@Qualifier("ipKeyResolver") KeyResolver resolver) {
            return (exchange, chain) -> resolver.resolve(exchange).flatMap(key -> {
                var headers = exchange.getResponse().getHeaders();
                headers.set("X-Probe-Rate-Key", key);
                headers.set("X-Probe-Scheme", exchange.getRequest().getURI().getScheme());
                headers.set("X-Probe-Authority", exchange.getRequest().getURI().getAuthority());
                return chain.filter(exchange);
            });
        }
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                byte[] body = String.valueOf(exchange.getRequestHeaders().getFirst("Forwarded"))
                        .getBytes(StandardCharsets.UTF_8);
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
