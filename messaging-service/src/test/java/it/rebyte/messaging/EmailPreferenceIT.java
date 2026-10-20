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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.messaging.dto.EmailPreferenceView;
import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class EmailPreferenceIT extends MessagingTestBase {

    private static final String PATH = "/notifications/email-preferences";

    @Autowired TestRestTemplate restTemplate;

    @Test
    void anonymousReadIsRejected() {
        ResponseEntity<String> response = restTemplate.getForEntity(PATH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anonymousWriteIsRejected() {
        ResponseEntity<String> response = restTemplate.exchange(
                PATH, HttpMethod.PUT, new HttpEntity<>(Map.of("emailEnabled", false), json(null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void userWithoutStoredPreferenceHasEmailEnabled() {
        ResponseEntity<EmailPreferenceView> response = get("carol");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new EmailPreferenceView(true));
    }

    @Test
    void switchingOffIsPersistedAndDoesNotAffectAnotherUser() {
        ResponseEntity<EmailPreferenceView> put = put("alice", Map.of("emailEnabled", false));

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody()).isEqualTo(new EmailPreferenceView(false));
        assertThat(get("alice").getBody()).isEqualTo(new EmailPreferenceView(false));
        assertThat(get("bob").getBody()).isEqualTo(new EmailPreferenceView(true));
    }

    @Test
    void switchingBackOnOverwritesThePreviousChoice() {
        put("dave", Map.of("emailEnabled", false));
        put("dave", Map.of("emailEnabled", true));

        assertThat(get("dave").getBody()).isEqualTo(new EmailPreferenceView(true));
    }

    @Test
    void emptyBodyIsABadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                PATH, HttpMethod.PUT, new HttpEntity<>(json("erin")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingFlagIsABadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                PATH, HttpMethod.PUT, new HttpEntity<>(Map.of(), json("erin")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<EmailPreferenceView> get(String sub) {
        return restTemplate.exchange(
                PATH, HttpMethod.GET, new HttpEntity<>(json(sub)), EmailPreferenceView.class);
    }

    private ResponseEntity<EmailPreferenceView> put(String sub, Map<String, Object> body) {
        return restTemplate.exchange(
                PATH, HttpMethod.PUT, new HttpEntity<>(body, json(sub)), EmailPreferenceView.class);
    }

    private static HttpHeaders json(String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sub != null) {
            headers.setBearerAuth(TestJwtSupport.mint(sub));
        }
        return headers;
    }
}
