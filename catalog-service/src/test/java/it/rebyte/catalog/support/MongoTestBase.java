package it.rebyte.catalog.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base condivisa per i test {@code *IT} di catalog-service che hanno bisogno
 * solo di MongoDB, non del broker o di Meilisearch (mirroring
 * {@code MySqlTestBase} in user-service).
 *
 * <p>{@link MongoDBContainer} si auto-inizializza come replica set a nodo
 * singolo — proprio cio che rende reali le transazioni multi-documento nei
 * test, senza il passo {@code mongo-init} manuale che
 * infra/docker-compose.yml usa in sviluppo.
 *
 * <p><b>{@code eureka.client.enabled=false} e {@code spring.rabbitmq.port=1}
 * </b>: portati da {@code MySqlTestBase} per le stesse ragioni documentate
 * nel suo Javadoc — nessun contesto {@code *IT} deve tentare una vera
 * registrazione Eureka contro {@code localhost:8761} durante {@code verify},
 * e senza un broker reale un {@code @RabbitListener} nel contesto (a partire
 * dal piano 02-02) farebbe fallire l'avvio con
 * {@code AmqpAuthenticationException} contro il RabbitMQ reale di sviluppo
 * — puntare alla porta 1 (dove nessun servizio ascolta mai) riporta quel
 * fallimento a un semplice connection-refused non fatale.
 *
 * <p><b>{@code rebyte.search.master-key} stand-in.</b> Nessun default in
 * {@code application.yml} per contratto (un deployment senza chiave deve
 * rifiutarsi di partire) — ma questo fa fallire l'avvio di OGNI contesto
 * {@code *IT} una volta che {@code MeilisearchConfig} esiste (piano 02-01
 * Task 2), incluse le IT che non toccano affatto Meilisearch, come
 * {@link it.rebyte.catalog.MongoDecimal128IT}. Stesso schema delle
 * proprieta stand-in di {@code rebyte.media.*} in {@code MySqlTestBase}:
 * mantiene intatto il contratto di produzione mentre lascia costruire il
 * contesto; nessun test che si affida a questo valore parla davvero con
 * Meilisearch — {@link CatalogInfraTestBase} sovrascrive con la chiave reale
 * del proprio container via {@code @DynamicPropertySource}.
 *
 * <p><b>{@code rebyte.search.bootstrap-enabled=false}.</b> A stand-in key
 * alone is not enough: {@code ListingIndexSettings} makes a REAL network
 * call to {@code rebyte.search.url} at startup (default
 * {@code http://localhost:7700}), which — with no override — is exactly the
 * developer's own local {@code rebyte-meilisearch} dev instance. Found via
 * genuine RED evidence: {@code MongoDecimal128IT} failed context startup
 * with {@code invalid_api_key} from a REAL Meilisearch server, not a
 * connection error, proving it had actually reached the developer's own
 * running container. This property disables that startup bootstrap for any
 * {@code *IT} using only this base; {@link CatalogInfraTestBase} leaves it
 * at its default (enabled), since it has a real, disposable Meilisearch
 * container to bootstrap against.
 *
 * <p><b>{@code @DirtiesContext(AFTER_CLASS)}</b>: stessa mitigazione
 * documentata su {@code MySqlTestBase} per il mismatch container/cache di
 * contesto — ogni classe {@code *IT} costruisce sempre un contesto fresco
 * contro il container davvero vivo in quel momento, invece di riusare un
 * {@code ApplicationContext} cache la cui pool/URI puntano a un container
 * gia riciclato.
 *
 * <p>Ogni contesto di test usa anche il Redis condiviso qui sotto. Le
 * proprieta host/port puntano intenzionalmente a una porta irraggiungibile:
 * se la connessione isolata non viene applicata, il test fallisce senza
 * poter raggiungere il Redis dello sviluppatore.
 */
@Testcontainers
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.rabbitmq.port=1",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "rebyte.search.master-key=test-master-key-not-real-1234",
        "rebyte.search.bootstrap-enabled=false",
        "rebyte.media.bucket=catalog-test-bucket",
        "rebyte.media.region=eu-south-1",
        "rebyte.media.distribution-domain=cdn.example.test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class MongoTestBase {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Container
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
}
