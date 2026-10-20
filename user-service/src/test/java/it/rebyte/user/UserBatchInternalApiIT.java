package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.user.domain.User;
import it.rebyte.user.repository.UserRepository;
import it.rebyte.user.support.MySqlTestBase;

@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class UserBatchInternalApiIT extends MySqlTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    private final List<String> provisionedSubs = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        provisionedSubs.forEach(userRepository::deleteById);
        provisionedSubs.clear();
    }

    @Test
    void returnsDistinctSummariesInFirstSeenOrderIncludingUnknownSubs() {
        String firstSub = provision("first_user");
        String secondSub = provision("second_user");
        String unknownSub = UUID.randomUUID().toString();

        ResponseEntity<List> response = restTemplate.getForEntity(
                "/internal/users?subs={subs}", List.class,
                firstSub + "," + unknownSub + "," + secondSub + "," + firstSub);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> summaries = response.getBody();
        assertThat(summaries).hasSize(3);
        assertThat(summaries).extracting(summary -> summary.get("sub"))
                .containsExactly(firstSub, unknownSub, secondSub);
        assertThat(summaries).extracting(summary -> summary.get("username"))
                .containsExactly("first_user", generatedHandle(unknownSub), "second_user");
    }

    @Test
    void acceptsExactlyFiftyDistinctSubs() {
        List<String> subs = java.util.stream.IntStream.range(0, 50)
                .mapToObj(ignored -> UUID.randomUUID().toString()).toList();

        ResponseEntity<List> response = restTemplate.getForEntity(
                "/internal/users?subs={subs}", List.class, String.join(",", subs));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(50);
    }

    @Test
    void rejectsMoreThanFiftyDistinctSubsWithStableCode() {
        List<String> subs = java.util.stream.IntStream.range(0, 51)
                .mapToObj(ignored -> UUID.randomUUID().toString()).toList();

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/internal/users?subs={subs}", Map.class, String.join(",", subs));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsEntry("title", "invalid-user-batch");
    }

    @Test
    void missingSubsReturnsBadRequest() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/internal/users", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankSubsReturnsBadRequestAfterTrimming() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/internal/users?subs={subs}", String.class, " , ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void singleSummaryEndpointRemainsAvailable() {
        String sub = UUID.randomUUID().toString();

        ResponseEntity<Map> response = restTemplate.getForEntity("/internal/users/" + sub, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("sub", sub)
                .containsEntry("username", generatedHandle(sub));
    }

    private String provision(String username) {
        String sub = UUID.randomUUID().toString();
        userRepository.saveAndFlush(new User(sub, username, Instant.now()));
        provisionedSubs.add(sub);
        return sub;
    }

    private static String generatedHandle(String sub) {
        return "user_" + sub.substring(0, 8).toLowerCase();
    }
}
