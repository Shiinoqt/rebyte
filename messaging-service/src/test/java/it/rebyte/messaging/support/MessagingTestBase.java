package it.rebyte.messaging.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for messaging-service integration tests: a disposable single-node
 * Mongo replica set (so multi-document transactions are real) and a
 * disposable Redis, both wired through {@code @ServiceConnection}.
 *
 * <p>Every endpoint a test does not provide a container for points at
 * {@code 127.0.0.1:1}, where nothing listens, so a missed override fails
 * fast instead of reaching a developer's running datastore or another
 * service. The Redis host and port are such canaries; the container
 * connection details replace them. Rabbit health is disabled because the
 * Rabbit canary is intentionally unreachable and would otherwise turn the
 * health endpoint DOWN; production health stays enabled.
 *
 * <p>The AWS keys, bucket, sender and unsubscribe secret are stand-ins that
 * satisfy the startup contract; no test using them talks to AWS.
 *
 * <p>{@code @DirtiesContext(AFTER_CLASS)} stops a cached context from
 * outliving the containers it was built against.
 */
@Testcontainers
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "management.health.rabbit.enabled=false",
        "spring.rabbitmq.port=1",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "rebyte.media.bucket=messaging-test-bucket",
        "rebyte.media.region=eu-south-1",
        "rebyte.messaging.aws.access-key-id=AKIAMESSAGINGTEST000",
        "rebyte.messaging.aws.secret-access-key=messaging-test-secret-not-real",
        "rebyte.ses.from-address=no-reply@example.test",
        "rebyte.ses.events.enabled=false",
        "rebyte.email.unsubscribe.secret=messaging-test-unsubscribe-secret-0123456789",
        "spring.cloud.openfeign.client.config.user-service.url=http://127.0.0.1:1",
        "spring.cloud.openfeign.client.config.order-service.url=http://127.0.0.1:1",
        "spring.cloud.openfeign.client.config.catalog-service.url=http://127.0.0.1:1"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class MessagingTestBase {

    @Container
    @ServiceConnection
    protected static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Container
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
}
