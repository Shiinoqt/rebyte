package it.rebyte.user.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base condivisa per i test {@code *IT} di user-service (D-23): un unico
 * container MySQL, riusato da ogni sottoclasse.
 *
 * <p>{@code eureka.client.enabled=false}: every {@code *IT} context also
 * runs a real Eureka client retrying registration against {@code
 * localhost:8761} (nothing listens there in a plain {@code verify} run) —
 * this override removes that noise.
 *
 * <p><b>{@code @DirtiesContext(AFTER_CLASS)} (D-23 stability fix, plan
 * 01-03).</b> With this plan the number of {@code *IT} classes grew from 2
 * to 5. Two classes with byte-identical {@code @SpringBootTest} annotations
 * (no {@code @DynamicPropertySource}) hit Spring's test-context CACHE: the
 * second class reuses the FIRST class's already-built
 * {@code ApplicationContext} — including its {@code HikariDataSource},
 * whose JDBC URL was fixed at creation time from {@code
 * MYSQL.getJdbcUrl()} at THAT moment. But the {@code @Testcontainers}
 * JUnit5 extension evaluates the {@code @Container} field again per test
 * CLASS and was observed recycling the container between classes (a new
 * container ID and mapped port each time), so the reused, cached
 * {@code ApplicationContext}'s pool kept pointing at the now-dead first
 * container while an unrelated second container sat there unused —
 * surfacing as an intermittent {@code CannotGetJdbcConnectionException}
 * ("Connection refused") on the second class. Forcing Spring to discard
 * the cached context after every {@code *IT} class removes the mismatch
 * entirely: each class always builds a fresh context against whatever
 * container is actually live at that moment.
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

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
}
