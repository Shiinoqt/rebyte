package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import it.rebyte.messaging.support.TestJwtSupport;

/**
 * Does not extend {@code MessagingTestBase}: a Mongo {@code @ServiceConnection}
 * would override {@code spring.mongodb.uri}, which is the property under test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
@Testcontainers
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "management.health.rabbit.enabled=false",
        "spring.rabbitmq.port=1",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "rebyte.media.bucket=messaging-test-bucket",
        "rebyte.messaging.aws.access-key-id=AKIAMESSAGINGTEST000",
        "rebyte.messaging.aws.secret-access-key=messaging-test-secret-not-real",
        "rebyte.ses.from-address=no-reply@example.test",
        "rebyte.ses.events.enabled=false",
        "rebyte.email.unsubscribe.secret=messaging-test-unsubscribe-secret-0123456789"
})
class MessagingMongoUriIT {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MongoTemplate mongo;
    @Autowired Environment environment;
    @Autowired TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void mongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("messaging_db"));
    }

    @Test
    void springMongoUriSelectsMessagingDatabase() {
        assertThat(mongo.getDb().getName()).isEqualTo("messaging_db");
        String retiredProperty = "spring." + "data." + "mongodb.uri";
        assertThat(environment.getProperty(retiredProperty)).isNull();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void healthEndpointReportsUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }
}
