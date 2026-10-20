package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import it.rebyte.messaging.email.UnsubscribeTokens;
import it.rebyte.messaging.error.InvalidUnsubscribeTokenException;

class UnsubscribeTokensTest {

    private static final String SECRET = "unit-test-unsubscribe-secret-0123456789";
    private final UnsubscribeTokens tokens = new UnsubscribeTokens(SECRET, Duration.ofDays(365));

    @Test
    void signedTokenReturnsItsSubject() {
        assertThat(tokens.verify(tokens.issue("alice"))).isEqualTo("alice");
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = tokens.issue("alice");
        String[] parts = token.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        assertInvalid(String.join(".", parts));
    }

    @Test
    void anotherSecretIsRejected() {
        assertInvalid(new UnsubscribeTokens("another-unit-test-secret-0123456789", Duration.ofDays(365)).issue("alice"));
    }

    @Test
    void wrongPurposeIsRejected() {
        assertInvalid(signed("alice", "access", Instant.now().plusSeconds(300)));
    }

    @Test
    void expiredTokenIsRejected() {
        assertInvalid(signed("alice", "email-unsubscribe", Instant.now().minusSeconds(300)));
        assertThatThrownBy(() -> new UnsubscribeTokens(SECRET, Duration.ofMinutes(-5)).issue("alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiryInsideDefaultClockSkewIsStillRejected() {
        assertInvalid(signed("alice", "email-unsubscribe", Instant.now().minusSeconds(1)));
    }

    @Test
    void missingExpiryAndBlankSubjectAreRejected() {
        assertInvalid(signed("alice", "email-unsubscribe", null));
        assertInvalid(signed(" ", "email-unsubscribe", Instant.now().plusSeconds(300)));
    }

    @Test
    void malformedAndNullTokensAreRejected() {
        assertInvalid("garbage");
        assertInvalid(null);
    }

    @Test
    void secretLengthUsesUtf8Bytes() {
        assertThatThrownBy(() -> new UnsubscribeTokens("x".repeat(31), Duration.ofDays(365)))
                .isInstanceOf(IllegalStateException.class);
        UnsubscribeTokens unicode = new UnsubscribeTokens("à".repeat(16), Duration.ofDays(365));
        assertThat(unicode.verify(unicode.issue("alice"))).isEqualTo("alice");
    }

    private String signed(String sub, String purpose, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(sub).claim("purpose", purpose)
                .issuedAt(Instant.now().minusSeconds(600));
        if (expiresAt != null) {
            claims.expiresAt(expiresAt);
        }
        return NimbusJwtEncoder.withSecretKey(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .build().encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> tokens.verify(token)).isInstanceOf(InvalidUnsubscribeTokenException.class);
    }
}
