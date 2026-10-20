package it.rebyte.catalog.support;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * A test-only {@link JwtDecoder} standing in for real Cognito/local-auth
 * token validation, added here (Rule 2) because catalog-service's
 * {@code pom.xml} deliberately declares no {@code nimbus-jose-jwt}
 * dependency — no signing minter is planned for this module the way
 * user-service's {@code LocalAuthKeyConfig}/{@code LocalAuthTokenController}
 * are. Registering a {@link JwtDecoder} bean makes Boot's
 * {@code JwtDecoderAutoConfiguration} back off entirely
 * ({@code @ConditionalOnMissingBean}), so no {@code issuer-uri} property is
 * needed either.
 *
 * <p>The "token" IS the {@code sub} claim, plus an optional {@code
 * .GROUP1+GROUP2} suffix carrying {@code cognito:groups} — {@link
 * #mint(String)}/{@link #mint(String, List)} and this decoder are two ends
 * of the same trivial encoding, never intended to resemble a real signed
 * JWT. The group suffix was added here (Rule 2, plan 02-02 Task 1) because
 * {@code CategoryController#create} is this service's first ADMIN-gated
 * endpoint — {@code CategoryAdminIT} needs a way to mint a token carrying
 * {@code cognito:groups: [ADMIN]}, which the original sub-only encoding had
 * no room for. {@link #mint(String)} keeps its original behaviour (no
 * groups claim at all) for every caller minted before this change.
 *
 * <p>Delimiters are {@code .} (sub/groups) and {@code +} (group/group), NOT
 * the more obvious {@code |}/{@code ,} — found via genuine RED evidence
 * (every {@code |}-encoded admin token got a bare 401, never reaching this
 * decoder at all): RFC 6750 §2.1 restricts a Bearer token's characters to
 * {@code token68} ({@code ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" /
 * "/"}), and Spring Security's bearer-token resolver rejects anything
 * outside that set before any {@link JwtDecoder} runs. {@code .} and {@code
 * +} are both in {@code token68}; {@code |} and {@code ,} are not. Tests
 * still activate the {@code local-auth} profile so {@link
 * it.rebyte.catalog.config.SecurityConfig}'s {@code securedChain} bean
 * exists at all.
 */
@TestConfiguration
public class TestJwtSupport {

    private static final String GROUPS_CLAIM = "cognito:groups";

    public static String mint(String sub) {
        return sub;
    }

    public static String mint(String sub, List<String> groups) {
        return groups.isEmpty() ? sub : sub + "." + String.join("+", groups);
    }

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return token -> {
            Instant now = Instant.now();
            String[] parts = token.split("\\.", 2);
            Map<String, Object> claims = new HashMap<>();
            claims.put("sub", parts[0]);
            if (parts.length > 1 && !parts[1].isBlank()) {
                claims.put(GROUPS_CLAIM, List.of(parts[1].split("\\+")));
            }
            return new Jwt(token, now, now.plusSeconds(300), Map.of("alg", "none"), claims);
        };
    }
}
