package it.rebyte.user.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base condivisa per i test {@code *IT} di user-service (D-23): un unico
 * container MySQL per JVM di test, con un database nuovo per ogni contesto
 * Spring.
 *
 * <p>{@code eureka.client.enabled=false}: every {@code *IT} context also
 * runs a real Eureka client retrying registration against {@code
 * localhost:8761} (nothing listens there in a plain {@code verify} run) —
 * this override removes that noise.
 *
 * <p>{@code @DirtiesContext(AFTER_CLASS)}: closing the context after each
 * class makes the next class load its own, so {@link #freshDatabase} runs
 * again and that class starts on an empty, freshly migrated database.
 * Without it, a class with byte-identical configuration would reuse the
 * cached context and see the previous class's rows, and {@link
 * RabbitTestBase} subclasses would reach a recycled broker.
 *
 * <p><b>{@code spring.rabbitmq.port=1} (plan 01-05, found via genuine RED
 * evidence).</b> {@code ReviewCreatedListener} is the first {@code
 * @RabbitListener} in this codebase. Once it existed, EVERY {@code
 * *IT} extending only this base (no real broker) failed context startup
 * with {@code AmqpAuthenticationException: ACCESS_REFUSED} — not because
 * anything was misconfigured, but because {@code application.yml}'s
 * {@code spring.rabbitmq.*} defaults (host {@code localhost}, port {@code
 * 5672}) happened to reach the REAL {@code docker-compose} RabbitMQ this
 * project's own dev environment runs, whose real credentials do not match
 * the empty/default ones a plain test JVM resolves. A listener container's
 * connection failure is normally non-fatal and silently retried in the
 * background (exactly like {@code RabbitAdmin}'s own declare-on-startup
 * failures, already tolerated before this plan) — but Spring AMQP treats
 * an AUTHENTICATION failure specifically as fatal, since retrying with the
 * same wrong credentials can never succeed. Pointing at port {@code 1}
 * (nothing ever listens on a privileged port locally) turns that
 * unreachable-broker case back into a plain, non-fatal connection-refused
 * — same class of fix as the {@code rebyte.media.*} stand-ins above: keep
 * the production contract intact while letting contexts build; {@link
 * RabbitTestBase} overrides this via its own {@code @ServiceConnection}
 * container, which takes priority over this property for any {@code *IT}
 * that genuinely needs a broker.
 */
@Testcontainers
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        // rebyte.media.* deliberately carries NO default in application.yml
        // (D-33..D-39): a deployment missing real values must refuse to start
        // rather than sign policies against a phantom bucket. That same absence
        // stops every *IT context from loading once AvatarService is on the
        // classpath, including ITs unrelated to avatars. These stand-ins keep
        // the production contract intact while letting contexts build; no test
        // asserted against them reaches S3 (S3PostPolicySignerTest is a pure
        // unit test over the signing algorithm).
        "rebyte.media.bucket=rebyte-test-bucket",
        "rebyte.media.distribution-domain=test.cloudfront.net",
        "spring.rabbitmq.port=1"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class MySqlTestBase {

    // root: CREATE DATABASE needs a privilege the image's non-root user is never granted.
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withUsername("root");

    // Started once per JVM and never stopped here, so Ryuk removes it when the JVM exits.
    static {
        MYSQL.start();
    }

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @DynamicPropertySource
    static void freshDatabase(DynamicPropertyRegistry registry) throws SQLException {
        String database = "user_" + DATABASES.incrementAndGet();
        try (Connection connection = MYSQL.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + MYSQL.getHost() + ":"
                + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT) + "/" + database);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
