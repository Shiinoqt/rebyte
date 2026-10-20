package it.rebyte.messaging.support;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * A test-only {@link JwtDecoder} standing in for Cognito or local-auth
 * validation. Registering a decoder bean makes Boot's decoder
 * auto-configuration back off, so no {@code issuer-uri} is contacted.
 *
 * <p>The token is the {@code sub} claim, optionally followed by
 * {@code .GROUP1+GROUP2} for {@code cognito:groups} and by
 * {@code ~epochSeconds} for the expiry (default: now plus 300 s).
 * {@link #mint}, {@link #mintExpiring} and this decoder are two ends of the
 * same encoding and do not resemble a signed JWT.
 *
 * <p>Only RFC 6750 {@code token68} characters may appear: Spring Security's
 * bearer-token resolver rejects others before any decoder runs. {@code .},
 * {@code +} and {@code ~} are all in that set. An expiry in the past is
 * rejected like a real expired token would be.
 */
@TestConfiguration
public class TestJwtSupport {

    private static final String GROUPS_CLAIM = "cognito:groups";
    private static final long DEFAULT_TTL_SECONDS = 300;

    public static String mint(String sub) {
        return sub;
    }

    public static String mint(String sub, List<String> groups) {
        return groups.isEmpty() ? sub : sub + "." + String.join("+", groups);
    }

    public static String mintExpiring(String sub, Instant expiresAt) {
        return sub + "~" + expiresAt.getEpochSecond();
    }

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return token -> {
            Instant now = Instant.now();
            String body = token;
            Instant expiresAt = now.plusSeconds(DEFAULT_TTL_SECONDS);
            int tilde = body.lastIndexOf('~');
            if (tilde >= 0) {
                expiresAt = Instant.ofEpochSecond(Long.parseLong(body.substring(tilde + 1)));
                body = body.substring(0, tilde);
            }
            if (!expiresAt.isAfter(now)) {
                throw new JwtException("Jwt expired");
            }
            String[] parts = body.split("\\.", 2);
            Map<String, Object> claims = new HashMap<>();
            claims.put("sub", parts[0]);
            if (parts.length > 1 && !parts[1].isBlank()) {
                claims.put(GROUPS_CLAIM, List.of(parts[1].split("\\+")));
            }
            return new Jwt(token, now, expiresAt, Map.of("alg", "none"), claims);
        };
    }
}
