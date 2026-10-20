package it.rebyte.messaging.email;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import it.rebyte.messaging.error.InvalidUnsubscribeTokenException;

@Component
public class UnsubscribeTokens {

    private static final String PURPOSE = "email-unsubscribe";
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration ttl;

    public UnsubscribeTokens(@Value("${rebyte.email.unsubscribe.secret}") String secret,
            @Value("${rebyte.email.unsubscribe.token-ttl}") Duration ttl) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("Unsubscribe secret must contain at least 32 UTF-8 bytes");
        }
        SecretKeySpec key = new SecretKeySpec(bytes, "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).build();
        // A decoder bean would replace the resource-server decoder for authenticated REST requests.
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.ttl = ttl;
    }

    public String issue(String sub) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(sub).claim("purpose", PURPOSE)
                .issuedAt(now).expiresAt(now.plus(ttl)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public String verify(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            if (!PURPOSE.equals(jwt.getClaimAsString("purpose")) || jwt.getSubject() == null
                    || jwt.getSubject().isBlank() || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(Instant.now())) {
                throw new InvalidUnsubscribeTokenException();
            }
            return jwt.getSubject();
        } catch (RuntimeException exception) {
            throw new InvalidUnsubscribeTokenException();
        }
    }
}
