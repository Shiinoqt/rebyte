package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;

import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class MessagingBootstrapIT extends MessagingTestBase {

    private static final String CANARY_URL = "http://127.0.0.1:1";

    @Autowired TestRestTemplate restTemplate;
    @Autowired Environment environment;
    @Autowired MongoClient mongoClient;
    @Autowired RedisConnectionFactory redisConnectionFactory;

    @Test
    @SuppressWarnings("rawtypes")
    void healthIsAnonymous() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void anonymousCallToProtectedRouteIsRejectedBeforeRouting() {
        ResponseEntity<String> response = restTemplate.getForEntity("/no-such-route", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authenticatedCallPassesSecurityAndReachesRouting() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint("alice"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/no-such-route", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void expiredTestTokenIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mintExpiring("alice", java.time.Instant.now().minusSeconds(60)));

        ResponseEntity<String> response = restTemplate.exchange(
                "/no-such-route", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void externalIntegrationsAreDisabledOrPointAtCanaries() {
        assertThat(environment.getProperty("eureka.client.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("rebyte.ses.events.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.rabbitmq.port")).isEqualTo("1");
        for (String service : new String[] {"user-service", "order-service", "catalog-service"}) {
            assertThat(environment.getProperty("spring.cloud.openfeign.client.config." + service + ".url"))
                    .isEqualTo(CANARY_URL);
        }
    }

    @Test
    void datastoresResolveToTheIsolatedContainers() {
        ServerAddress mongoAddress = mongoClient.getClusterDescription().getClusterSettings().getHosts().get(0);
        assertThat(mongoAddress.getPort()).isEqualTo(MONGO.getFirstMappedPort());

        assertThat(redisConnectionFactory).isInstanceOf(LettuceConnectionFactory.class);
        LettuceConnectionFactory lettuce = (LettuceConnectionFactory) redisConnectionFactory;
        assertThat(lettuce.getPort()).isEqualTo(REDIS.getMappedPort(6379));
    }
}
