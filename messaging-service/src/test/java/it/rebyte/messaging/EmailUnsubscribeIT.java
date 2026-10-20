package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Instant;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;

import it.rebyte.messaging.email.UnsubscribeTokens;
import it.rebyte.messaging.repository.EmailPreferenceRepository;
import it.rebyte.messaging.service.EmailPreferenceService;
import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class EmailUnsubscribeIT extends MessagingTestBase {

    private static final String PATH = "/notifications/email-preferences/unsubscribe";
    @Autowired TestRestTemplate rest;
    @Autowired UnsubscribeTokens tokens;
    @Autowired EmailPreferenceService preferences;
    @Autowired EmailPreferenceRepository repository;
    @Value("${rebyte.email.unsubscribe.secret}") String secret;

    @BeforeEach
    void resetPreferences() {
        repository.deleteAll();
    }

    @Test
    void getIsAnonymousScannerSafeAndDoesNotValidateCredentials() {
        String token = tokens.issue("alice");
        ResponseEntity<String> valid = rest.getForEntity(path(token), String.class);
        ResponseEntity<String> invalid = rest.getForEntity(path("garbage"), String.class);
        assertHtml(valid);
        assertHtml(invalid);
        assertThat(valid.getBody()).contains("<head><meta name=\"referrer\" content=\"strict-origin\"></head>");
        assertThat(valid.getBody()).contains("<form method=\"post\"", "<button", path(token));
        assertThat(valid.getBody().replace(token, "TOKEN")).isEqualTo(invalid.getBody().replace("garbage", "TOKEN"));
        assertThat(preferences.isEnabled("alice")).isTrue();
        assertThat(repository.count()).isZero();
    }

    @Test
    void getEscapesUntrustedTokenInFormAction() {
        ResponseEntity<String> response = rest.getForEntity(URI.create(path("\"><script>alert(1)</script>&x=1")), String.class);
        assertHtml(response);
        assertThat(response.getBody()).doesNotContain("<script>", "\"><script>")
                .contains("%22%3E%3Cscript%3E");
        assertThat(repository.count()).isZero();
    }

    @Test
    void anonymousOneClickPostChangesOnlyTokenSubjectAndIsIdempotent() {
        String token = tokens.issue("alice");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = rest.postForEntity(path(token),
                new HttpEntity<>("List-Unsubscribe=One-Click", headers), String.class);
        assertHtml(response);
        assertThat(response.getBody()).contains("You will no longer receive message and release emails from Rebyte.",
                "Order emails are still sent.");
        assertThat(preferences.isEnabled("alice")).isFalse();
        assertThat(preferences.isEnabled("bob")).isTrue();
        assertHtml(rest.postForEntity(path(token), null, String.class));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void emptyBodyPostAlsoUnsubscribes() {
        assertHtml(rest.postForEntity(path(tokens.issue("alice")), null, String.class));
        assertThat(preferences.isEnabled("alice")).isFalse();
        assertThat(preferences.isEnabled("bob")).isTrue();
    }

    @Test
    void tamperedTokenFailsWithoutChangingPreference() {
        String[] parts = tokens.issue("alice").split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        assertInvalid(String.join(".", parts));
    }

    @Test
    void expiredTokenFailsWithoutChangingPreference() {
        assertInvalid(signed("email-unsubscribe", Instant.now().minusSeconds(300)));
    }

    @Test
    void wrongPurposeFailsWithoutChangingPreference() {
        assertInvalid(signed("access", Instant.now().plusSeconds(300)));
    }

    private void assertInvalid(String token) {
        ResponseEntity<String> response = rest.postForEntity(path(token), null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.getBody()).contains("invalid-unsubscribe-token");
        assertThat(preferences.isEnabled("alice")).isTrue();
        assertThat(repository.count()).isZero();
    }

    private String signed(String purpose, Instant expiry) {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("alice").claim("purpose", purpose)
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiry).build();
        return NimbusJwtEncoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .build().encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private static String path(String token) {
        return UriComponentsBuilder.fromPath(PATH).queryParam("token", "{token}")
                .encode().buildAndExpand(token).toUriString();
    }

    private static void assertHtml(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
    }
}
