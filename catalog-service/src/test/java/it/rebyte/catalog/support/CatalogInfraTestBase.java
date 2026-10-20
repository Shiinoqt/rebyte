package it.rebyte.catalog.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * Extends {@link MongoTestBase} with a real broker and a real Meilisearch
 * instance for the end-to-end tracer path (Task 2) and the lease-reaper
 * concurrency test (Task 3) — mirroring {@code RabbitTestBase}'s "one base
 * gets you every real container this module needs" idiom.
 *
 * <p><b>RabbitMQ</b>: {@code @ServiceConnection} auto-wires Testcontainers'
 * throwaway broker credentials, overriding the {@code spring.rabbitmq.port=1}
 * stand-in {@link MongoTestBase} sets for contexts that don't need a real
 * broker at all.
 *
 * <p><b>Meilisearch</b>: there is no official Testcontainers module for it
 * (02-RESEARCH.md, verified this session) — this {@link GenericContainer} is
 * the deliberate, recorded substitute, pinned to {@code v1.53.2}, the exact
 * tag infra/docker-compose.yml runs. Tests must never point at the
 * developer's own {@code rebyte-meilisearch} instance; the container's own
 * URL and a fixed test master key (30 bytes, above Meilisearch's 16-byte
 * minimum) are published into the Spring context via
 * {@code @DynamicPropertySource}, overriding {@link MongoTestBase}'s
 * stand-in key with the one this container actually enforces. The wait
 * strategy polls {@code GET /health} for a clean 200 rather than trusting
 * the container's own started-log line, since Meilisearch can accept TCP
 * connections slightly before its HTTP API is ready to serve.
 *
 * <p><b>{@code rebyte.search.bootstrap-enabled} must be re-enabled here.</b>
 * Spring's {@code @TestPropertySource} is INHERITED across a test class
 * hierarchy — {@link MongoTestBase}'s {@code bootstrap-enabled=false}
 * therefore also applies to every subclass of THIS class, undoing exactly
 * the bootstrap this class exists to exercise. Found via genuine RED
 * evidence: {@code PublishToSearchTracerIT} and {@code SearchTextMatchingIT}
 * both started failing with {@code index_not_found} the moment that
 * property was added to {@code MongoTestBase}, because
 * {@code ListingIndexSettings} never ran to create the index. A
 * {@code @DynamicPropertySource} entry has the highest precedence of any
 * property source, so re-asserting {@code true} here reliably wins over the
 * inherited {@code @TestPropertySource} value regardless of annotation
 * ordering.
 */
public abstract class CatalogInfraTestBase extends MongoTestBase {

    private static final String MEILI_TEST_MASTER_KEY = "catalog-it-meilisearch-master-key-2026";

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    @Container
    static final GenericContainer<?> MEILISEARCH = new GenericContainer<>(DockerImageName.parse("getmeili/meilisearch:v1.53.2"))
            .withExposedPorts(7700)
            .withEnv("MEILI_MASTER_KEY", MEILI_TEST_MASTER_KEY)
            .withEnv("MEILI_ENV", "development")
            .waitingFor(Wait.forHttp("/health").forStatusCode(200));

    @DynamicPropertySource
    static void meilisearchProperties(DynamicPropertyRegistry registry) {
        registry.add("rebyte.search.url",
                () -> "http://" + MEILISEARCH.getHost() + ":" + MEILISEARCH.getMappedPort(7700));
        registry.add("rebyte.search.master-key", () -> MEILI_TEST_MASTER_KEY);
        registry.add("rebyte.search.bootstrap-enabled", () -> "true");
    }
}
