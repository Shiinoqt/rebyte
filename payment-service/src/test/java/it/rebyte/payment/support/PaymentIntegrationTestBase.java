package it.rebyte.payment.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Shared {@code *IT} infrastructure for payment-service (03-05-PLAN.md Task
 * 1 and Task 2): one real MySQL container per test JVM with a fresh
 * database per Spring context, Eureka/RabbitMQ silenced the same way
 * order-service's {@code OrderIntegrationTestBase} does, a test-only {@link JwtDecoder} (payment-service declares no {@code
 * nimbus-jose-jwt} dependency, same as order-service), and two independent
 * official WireMock instances (03-05-PLAN.md Task 1: "test-scoped official
 * WireMock artifact") — one standing in for the real PayPal sandbox API,
 * one standing in for order-service's internal capture-authorization
 * endpoint (Task 2). No live network call and no real PayPal/order-service
 * credentials are ever required to run this module's tests.
 *
 * <p>{@code @DirtiesContext(AFTER_CLASS)}: closing the context after each
 * class makes the next class load its own, so {@link #freshDatabase} runs
 * again and that class starts on an empty, freshly migrated database, and
 * {@code wireMockProperties} points it at the WireMock ports of its own run.
 * Without it, a class with byte-identical configuration would reuse the
 * cached context, see the previous class's rows, and call WireMock ports
 * from a different test class.
 */
@Testcontainers
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        // Nothing in this plan's tests binds a queue or awaits an event;
        // this only stops the real dev RabbitMQ (reachable at the default
        // localhost:5672 per infra/docker-compose.yml) from being contacted
        // during context startup.
        "spring.rabbitmq.port=1",
        // Never real PayPal credentials — WireMock does not validate this
        // Basic-auth header content, only that one is present.
        "rebyte.paypal.client-id=test-client-id",
        "rebyte.paypal.client-secret=test-client-secret",
        "rebyte.paypal.connect-timeout-ms=1000",
        // Short read timeout so a deliberately delayed WireMock stub proves
        // a genuine client-side timeout within test time budgets.
        "rebyte.paypal.read-timeout-ms=800",
        "rebyte.checkout.return-base-url=https://example.invalid"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PaymentIntegrationTestBase {

    // root: CREATE DATABASE needs a privilege the image's non-root user is never granted.
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withUsername("root");

    // Started once per JVM and never stopped here, so Ryuk removes it when the JVM exits.
    static {
        MYSQL.start();
    }

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @DynamicPropertySource
    static void freshDatabase(DynamicPropertyRegistry registry) throws SQLException {
        String database = "payment_" + DATABASES.incrementAndGet();
        try (Connection connection = MYSQL.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT) + "/" + database);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** Stands in for the real PayPal sandbox REST API (OAuth token, Orders v2 create/capture). */
    @RegisterExtension
    public static final WireMockExtension PAYPAL = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /** Stands in for order-service's {@code POST /internal/orders/{id}/capture-authorizations} (Task 2). */
    @RegisterExtension
    public static final WireMockExtension ORDER_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void wireMockProperties(DynamicPropertyRegistry registry) {
        registry.add("rebyte.paypal.base-url", () -> PAYPAL.getRuntimeInfo().getHttpBaseUrl());
        registry.add("spring.cloud.openfeign.client.config.order-service.url",
                () -> ORDER_SERVICE.getRuntimeInfo().getHttpBaseUrl());
    }

    /**
     * local-auth-style bearer token: the {@code sub} claim only, decoded by
     * {@link StubJwtConfig}. No signing, no groups claim — the buyer-facing
     * capture flow this plan tests never checks a Cognito group.
     */
    public static String token(String sub) {
        return sub;
    }

    /** Registers a test-only {@link JwtDecoder}, overriding Boot's issuer-uri-based auto-configuration entirely. */
    @TestConfiguration
    public static class StubJwtConfig {

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> {
                Instant now = Instant.now();
                return new Jwt(token, now, now.plusSeconds(300), Map.of("alg", "none"), Map.of("sub", token));
            };
        }
    }
}
