package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.rabbitmq.port=1",
        "rebyte.search.master-key=test-master-key-not-real-1234",
        "rebyte.search.bootstrap-enabled=false",
        "rebyte.media.bucket=catalog-test-bucket",
        "rebyte.media.region=eu-south-1",
        "rebyte.media.distribution-domain=cdn.example.test",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1"
})
class CatalogMongoUriIT {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MongoTemplate mongo;
    @Autowired Environment environment;

    @DynamicPropertySource
    static void mongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("catalog_db"));
    }

    @Test
    void springMongoUriSelectsCatalogDatabase() {
        assertThat(mongo.getDb().getName()).isEqualTo("catalog_db");
        String retiredProperty = "spring." + "data." + "mongodb.uri";
        assertThat(environment.getProperty(retiredProperty)).isNull();
    }
}
